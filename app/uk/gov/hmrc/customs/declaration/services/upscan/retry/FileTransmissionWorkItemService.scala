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

package uk.gov.hmrc.customs.declaration.services.upscan.retry

import java.net.URL

import com.google.inject.ImplementedBy
import javax.inject.Inject
import uk.gov.hmrc.customs.declaration.connectors.filetransmission.FileTransmissionConnector
import uk.gov.hmrc.customs.declaration.logging.DeclarationsLogger
import uk.gov.hmrc.customs.declaration.model.filetransmission._
import uk.gov.hmrc.customs.declaration.model.upscan.RetryFileUploadMetadataWorkItem
import uk.gov.hmrc.customs.declaration.repo.RetryFileUploadMetadataWorkItemRepo
import uk.gov.hmrc.customs.declaration.services.{DateTimeService, DeclarationsConfigService}
import uk.gov.hmrc.http.{BadGatewayException, NotFoundException}
import uk.gov.hmrc.workitem.{Failed, PermanentlyFailed, Succeeded, WorkItem}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@ImplementedBy(classOf[FileTransmissionWorkItemServiceImpl])
trait FileTransmissionWorkItemService {
  def processOne(): Future[Boolean]
}

class FileTransmissionWorkItemServiceImpl @Inject()(repository: RetryFileUploadMetadataWorkItemRepo,
                                                    fileTransmissionConnector: FileTransmissionConnector,
                                                    dateTimeService: DateTimeService,
                                                    config: DeclarationsConfigService,
                                                    logger: DeclarationsLogger)
                                                   (implicit ec: ExecutionContext) extends FileTransmissionWorkItemService {
  val failuresBeforePermanentlyFailed = 2
  val secondsBeforeAvailableForRetry = 30

  def processOne(): Future[Boolean] = {

    val failedBefore = dateTimeService.nowUtc()
    val availableBefore = failedBefore

    logger.debugWithoutRequestContext(s"polling....")

    val eventuallyProcessedOne: Future[Boolean] = repository.pullOutstanding(failedBefore, availableBefore).flatMap{
      case Some(firstOutstandingItem) =>
        processWorkItem(firstOutstandingItem).map{_ =>
          true
        }
      case None =>
        Future.successful(false)
    }
    eventuallyProcessedOne
  }

  private def processWorkItem(item: WorkItem[RetryFileUploadMetadataWorkItem]): Future[Unit] = {
    logger.debugWithoutRequestContext(s"poller has pulled file metadata with fileReference ${item.item.fileReference}")

    fileTransmissionConnector.sendForPoller(fileTransmissionAdapter(item.item)).map { _ =>
      logger.debugWithoutRequestContext(s"successfully called file-transmission service $item")
      repository.complete(item, Succeeded)
      ()
    }.recover {
      case e @ (_ : NotFoundException | _ : BadGatewayException) =>
        if (item.failureCount > failuresBeforePermanentlyFailed) {
          logger.errorWithoutRequestContext(s"unable to process work item with fileReference ${item.item.fileReference} and passed max failure threshold so setting to permanently-failed")
          repository.complete(item, PermanentlyFailed)
        } else {
          logger.errorWithoutRequestContext(s"unable to process work item with fileReference ${item.item.fileReference} so setting to failed")
          repository.markAs(item, Failed, dateTimeService.nowUtc().plusSeconds(secondsBeforeAvailableForRetry))
        }
      case NonFatal(e) =>
        Future.failed(e)
    }
  }

  private def fileTransmissionAdapter(workItem: RetryFileUploadMetadataWorkItem): FileTransmission = {

    FileTransmission(FileTransmissionBatch(workItem.batchId, workItem.fileCount), new URL(s"${config.fileUploadConfig.fileTransmissionCallbackUrl}${workItem.csId}"),
      FileTransmissionFile(workItem.fileReference, workItem.maybeCallbackFields.get.name,
        workItem.maybeCallbackFields.get.mimeType, workItem.maybeCallbackFields.get.checksum,
        workItem.maybeCallbackFields.get.outboundLocation, workItem.sequenceNumber, workItem.size,
        workItem.maybeCallbackFields.get.uploadTimestamp), FileTransmissionInterface("DEC64", "1.0.0"), extractFileProperties(workItem))
  }

  private def extractFileProperties(workItem: RetryFileUploadMetadataWorkItem): Seq[FileTransmissionProperty] = {
    val fileProperties = Seq("DeclarationId" -> workItem.declarationId.toString, "Eori" -> workItem.eori.toString)
      .map(t => FileTransmissionProperty(name = t._1, value = t._2))
    if (workItem.documentType.isDefined) fileProperties :+ FileTransmissionProperty("DocumentType", workItem.documentType.get.toString) else fileProperties
  }
}
