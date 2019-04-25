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

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.event.Logging
import javax.inject.Inject
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.customs.declaration.logging.DeclarationsLogger

import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.util.{Failure, Success}

class FileTransmissionWorkItemProcessingScheduler @Inject()(queueProcessor: FileTransmissionWorkItemService,
                                                            logger: DeclarationsLogger)(
                                                             implicit actorSystem: ActorSystem,
                                                             applicationLifecycle: ApplicationLifecycle
                                                           ) {

  val pollingInterval: FiniteDuration = FiniteDuration(20000, MILLISECONDS)
  val retryAfterFailureInterval: FiniteDuration = FiniteDuration(40000, MILLISECONDS)

  case object Poll

  class ContinuousPollingActor extends Actor {

    import context.dispatcher
    val log = Logging(context.system, this)

    override def receive: Receive = {
      case Poll =>
        queueProcessor.processOne() andThen {
          case Success(true) =>
            self ! Poll
          case Success(false) =>
            log.info("Waiting")
            context.system.scheduler.scheduleOnce(pollingInterval, self, Poll)
          case Failure(f) =>
            log.error(f, s"Queue processing failed")
            context.system.scheduler.scheduleOnce(retryAfterFailureInterval, self, Poll)
        }
    }
  }

  private val pollingActor = actorSystem.actorOf(Props(new ContinuousPollingActor()))

  pollingActor ! Poll

  def shutDown(): Unit = pollingActor ! PoisonPill

  applicationLifecycle.addStopHook { () =>
    shutDown()
    Future.successful(())
  }

}
