/*
 * Copyright 2021 HM Revenue & Customs
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

import java.time.LocalDateTime

import akka.actor.ActorSystem
import com.google.inject._
import org.joda.time.DateTime
import play.api.http.HeaderNames._
import play.api.http.MimeTypes
import uk.gov.hmrc.customs.api.common.config.ServiceConfigProvider
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.declaration.config.DeclarationCircuitBreaker
import uk.gov.hmrc.customs.declaration.controllers.CustomHeaderNames.{XConversationIdHeaderName, XCorrelationIdHeaderName}
import uk.gov.hmrc.customs.declaration.http.Non2xxResponseException
import uk.gov.hmrc.customs.declaration.logging.DeclarationsLogger
import uk.gov.hmrc.customs.declaration.model._
import uk.gov.hmrc.customs.declaration.model.actionbuilders.AuthorisedRequest
import uk.gov.hmrc.customs.declaration.services.DeclarationsConfigService
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.logging.Authorization
import uk.gov.hmrc.http.{HttpClient, _}

import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

@Singleton
class DeclarationStatusConnector @Inject() (val http: HttpClient,
                                            val logger: DeclarationsLogger,
                                            val serviceConfigProvider: ServiceConfigProvider,
                                            val config: DeclarationsConfigService,
                                            override val cdsLogger: CdsLogger,
                                            override val actorSystem: ActorSystem)
                                           (implicit val ec: ExecutionContext)
  extends DeclarationCircuitBreaker with HttpErrorFunctions {

  override val configKey = "declaration-status"

  override lazy val numberOfCallsToTriggerStateChange = config.declarationsCircuitBreakerConfig.numberOfCallsToTriggerStateChange
  override lazy val unstablePeriodDurationInMillis = config.declarationsCircuitBreakerConfig.unstablePeriodDurationInMillis
  override lazy val unavailablePeriodDurationInMillis = config.declarationsCircuitBreakerConfig.unavailablePeriodDurationInMillis

  def send[A](xmlToSend: NodeSeq,
              date: DateTime,
              correlationId: CorrelationId,
              apiVersion: ApiVersion)
             (implicit ar: AuthorisedRequest[A]): Future[HttpResponse] = {

    val config = Option(serviceConfigProvider.getConfig(s"${apiVersion.configPrefix}$configKey")).getOrElse(throw new IllegalArgumentException("config not found"))
    val bearerToken = "Bearer " + config.bearerToken.getOrElse(throw new IllegalStateException("no bearer token was found in config"))
    implicit val hc: HeaderCarrier = HeaderCarrier(extraHeaders = getHeaders(date, ar.conversationId, correlationId), authorization = Some(Authorization(bearerToken)))
    val startTime = LocalDateTime.now
    withCircuitBreaker(post(xmlToSend, config.url)).map{
      response =>
        logCallDuration(startTime)
        logger.debug(s"Declaration status response code: ${response.status} and response body: ${response.body}")
      response
    }
  }

  private def getHeaders(date: DateTime, conversationId: ConversationId, correlationId: CorrelationId) = {
    Seq(
        (X_FORWARDED_HOST, "MDTP"),
        (XCorrelationIdHeaderName, correlationId.toString),
        (XConversationIdHeaderName, conversationId.toString),
        (DATE, date.toString("EEE, dd MMM yyyy HH:mm:ss z")),
        (CONTENT_TYPE, MimeTypes.XML + "; charset=utf-8"),
        (ACCEPT, MimeTypes.XML)
    )
  }

  private def post[A](xml: NodeSeq, url: String)(implicit ar: AuthorisedRequest[A], hc: HeaderCarrier) = {
    logger.debug(s"Sending request to $url. Headers ${hc.headers} Payload:\n$xml")

    http.POSTString[HttpResponse](url, xml.toString()).map { response =>
      response.status match {
        case status if is2xx(status) =>
          response

        case status => //1xx, 3xx, 4xx, 5xx
          logger.error(s"Failed status backend call response body=${response.body}")
          throw new Non2xxResponseException(status)
      }
    }
      .recoverWith {
        case httpError: HttpException =>
          logger.error(s"Call to declaration status failed. url=$url status=${httpError.responseCode}")
          Future.failed(httpError)
        case e: Throwable =>
          logger.error(s"Call to declaration status failed. url=$url")
          Future.failed(e)
      }
  }
}
