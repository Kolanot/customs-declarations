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

package uk.gov.hmrc.customs.declaration.services.filetransmission

import javax.inject.Inject
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.declaration.connectors._
import uk.gov.hmrc.customs.declaration.model.FileTransmissionEnvelope
import uk.gov.hmrc.customs.declaration.services.filetransmission.queue._
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

class TransmissionRequestProcessingJob @Inject()(
                                                  connector: FileTransmissionConnector,
                                                  logger: CdsLogger)(implicit ec: ExecutionContext)
    extends QueueJob {

  override def process(item: FileTransmissionEnvelope,
                       canRetry: Boolean): Future[ProcessingResult] = {
    implicit val hc = HeaderCarrier()

    for (result <- connector.send(item.request)) yield {
      result match {
        case FileTransmissionRequestSuccessful =>
          logger.info(s"Request ${item.request} processed successfully")
          ProcessingSuccessful
        case FileTransmissionRequestFatalError(error) =>
          logger.warn(
            s"Processing request ${item.request} failed - non recoverable error",
            error)
          ProcessingFailedDoNotRetry(error)
        case FileTransmissionRequestError(error) if canRetry =>
          logger.warn(s"Processing request ${item.request} failed", error)
          ProcessingFailed(error)
        case FileTransmissionRequestError(error) =>
          logger.warn(s"Processing request ${item.request} failed", error)
          ProcessingFailedDoNotRetry(error)


      }
    }

  }
}
