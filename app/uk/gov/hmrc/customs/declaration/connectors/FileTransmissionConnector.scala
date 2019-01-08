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

package uk.gov.hmrc.customs.declaration.connectors

import com.google.inject._
import play.mvc.Http.HeaderNames._
import play.mvc.Http.MimeTypes.JSON
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.declaration.model.FileTransmission
import uk.gov.hmrc.customs.declaration.services.DeclarationsConfigService
import uk.gov.hmrc.http.{HeaderCarrier, HttpException, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

sealed trait FileTransmissionRequestResult

case object FileTransmissionRequestSuccessful extends FileTransmissionRequestResult

case class FileTransmissionRequestError(e: Throwable) extends FileTransmissionRequestResult

case class FileTransmissionRequestFatalError(e: Throwable) extends FileTransmissionRequestResult

@Singleton
class FileTransmissionConnector @Inject()(http: HttpClient,
                                          logger: CdsLogger,
                                          config: DeclarationsConfigService) {

  private implicit val hc: HeaderCarrier = HeaderCarrier(
    extraHeaders = Seq(ACCEPT -> JSON, CONTENT_TYPE -> JSON, USER_AGENT -> "customs-declarations")
  )

  def send[A](request: FileTransmission): Future[FileTransmissionRequestResult] = {
    post(request, config.batchFileUploadConfig.fileTransmissionBaseUrl)
  }

  private def post[A](request: FileTransmission, url: String): Future[FileTransmissionRequestResult] = {

    logger.debug(s"Sending request to file transmission service. Url: $url Payload: ${request.toString}")
    val a: Future[FileTransmissionRequestResult] = http.POST[FileTransmission, HttpResponse](url, request).map { _ =>
      logger.info(s"[conversationId=${request.file.reference}]: file transmission request sent successfully")
      FileTransmissionRequestSuccessful
    }.recover {
      case httpError: HttpException => FileTransmissionRequestError(httpError)
      case e: Throwable =>
        logger.error(s"Call to file transmission failed. url=$url")
        FileTransmissionRequestError(e)
    }
    a
  }

}
