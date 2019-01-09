/*
 * Copyright 2019 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.customs.declaration.services.filetransmission.queue

import java.time.Clock

import cats.data.OptionT
import cats.implicits._
import javax.inject.Inject
import org.joda.time.{DateTime, Duration}
import uk.gov.hmrc.customs.declaration.logging.DeclarationsLogger
import uk.gov.hmrc.customs.declaration.model.FileTransmissionEnvelope
import uk.gov.hmrc.customs.declaration.services.filetransmission.util.JodaTimeConverters._
import uk.gov.hmrc.workitem._
import uk.gov.hmrc.workitem.InProgress

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

sealed trait ProcessingResult
case object ProcessingSuccessful extends ProcessingResult
case class ProcessingFailed(error: Throwable) extends ProcessingResult
case class ProcessingFailedDoNotRetry(error: Throwable) extends ProcessingResult

trait QueueJob {
  def process(item: FileTransmissionEnvelope, canRetry: Boolean): Future[ProcessingResult]
}

trait WorkItemService {
  def enqueue(request: FileTransmissionEnvelope): Future[Unit]

  def processOne(): Future[Boolean]

  def enqueueAndProcess(request: FileTransmissionEnvelope): Future[Unit]
}

class MongoBackedWorkItemService @Inject()(
    repository: TransmissionRequestWorkItemRepository,
    queueJob: QueueJob,
    clock: Clock,
    logger: DeclarationsLogger)(implicit ec: ExecutionContext)
    extends WorkItemService {

  def enqueue(request: FileTransmissionEnvelope): Future[Unit] = {
    repository.pushNew(request, now()).onComplete {
      case Success(workItem) => logger.debugWithoutRequestContext(s"POC => enqueuing item for later processing with id: ${workItem.id.stringify}")
      case Failure(exception) => logger.debugWithoutRequestContext(s"Failed store work item due to: ${exception.getMessage}")
    }
    Future.successful(())
  }

  private def inProgress(item: FileTransmissionEnvelope): ProcessingStatus = InProgress
  def enqueueAndProcess(request: FileTransmissionEnvelope): Future[Unit] = {

    logger.debugWithoutRequestContext(s"POC => enqueuing work item with status ${InProgress.name} for synchronous processing: $request")
    val futureItem = repository.pushNew(request, now(), inProgress _)
    futureItem.onComplete {
      case Success(workItem) => processWorkItem(workItem)
      case Failure(exception) => logger.debugWithoutRequestContext(s"Failed store work item due to: ${exception.getMessage}")
    }
    Future.successful(())
  }

  def processOne(): Future[Boolean] = {

    val failedBefore = now() //we don't use this
    val availableBefore = now()

    val result: OptionT[Future, Unit] = for {
      firstOutstandingItem <-
        OptionT(repository.pullOutstanding(failedBefore, availableBefore))
      _ <- {
        logger.debugWithoutRequestContext(s"POC => dequeued work item with id: ${firstOutstandingItem.id.stringify}")
        OptionT.liftF(processWorkItem(firstOutstandingItem))
      }
    } yield ()

    val somethingHasBeenProcessed = result.value.map(_.isDefined)

    somethingHasBeenProcessed
  }

  private def processWorkItem(workItem: WorkItem[FileTransmissionEnvelope]): Future[Unit] = {
    logger.debugWithoutRequestContext(s"POC => processing work item with id: ${workItem.id.stringify}")
    val request = workItem.item

    val nextRetryTime: DateTime = nextAvailabilityTime(workItem) //adds 2s
    val canRetry = nextRetryTime < timeToGiveUp(workItem) //adds 4h

    for (processingResult <- queueJob.process(request, canRetry)) yield {
      processingResult match {
        case ProcessingSuccessful =>
          repository.complete(workItem.id, Succeeded)
          logger.debugWithoutRequestContext(s"POC => marking as complete work item with id: ${workItem.id.stringify}")
        case ProcessingFailed(_) =>
          repository.markAs(workItem.id, Failed, Some(nextRetryTime))
          logger.debugWithoutRequestContext(s"POC => marking as failed work item with id: ${workItem.id.stringify}")
        case ProcessingFailedDoNotRetry(_) =>
          repository.markAs(workItem.id, PermanentlyFailed, None)
          logger.debugWithoutRequestContext(s"POC => marking as permanently failed work item with id: ${workItem.id.stringify}")
      }
    }
  }

  private def now(): DateTime = clock.nowAsJoda

  private def nextAvailabilityTime[T](workItem: WorkItem[T]): DateTime = {
    //TODO MC use akka predefined strategies
    val delay = Duration.standardSeconds(2) //TODO MC hardcoded
    now() + delay
  }

  private def timeToGiveUp(workItem: WorkItem[FileTransmissionEnvelope]): DateTime = {
    val deliveryWindowDuration = Duration.standardHours(4) //TODO MC hardcoded
    workItem.receivedAt + deliveryWindowDuration
  }

}
