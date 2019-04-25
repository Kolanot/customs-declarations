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

package uk.gov.hmrc.customs.declaration.repo

import com.google.inject.ImplementedBy
import javax.inject.{Inject, Singleton}
import org.joda.time.{DateTime, Duration}
import play.api.Configuration
import play.api.libs.functional.syntax.{unlift, _}
import play.api.libs.json.{Format, Json, Reads, __}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.customs.declaration.logging.DeclarationsLogger
import uk.gov.hmrc.customs.declaration.model.SubscriptionFieldsId
import uk.gov.hmrc.customs.declaration.model.upscan.{CallbackFields, FileReference, RetryFileUploadMetadataWorkItem}
import uk.gov.hmrc.customs.declaration.services.DateTimeService
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.workitem._

import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[RetryFileUploadMetadataWorkItemMongoRepo])
trait RetryFileUploadMetadataWorkItemRepo {

  def saveWithStatus(workItem: RetryFileUploadMetadataWorkItem, processingStatus: ProcessingStatus): Future[WorkItem[RetryFileUploadMetadataWorkItem]]

  def update(csId: SubscriptionFieldsId,
             reference: FileReference,
             callbackFields: CallbackFields,
             processingStatus: ProcessingStatus): Future[Option[WorkItem[RetryFileUploadMetadataWorkItem]]]

  def pullOutstanding(failedBefore: DateTime,
                      availableBefore: DateTime)
                     (implicit ec: ExecutionContext): Future[Option[WorkItem[RetryFileUploadMetadataWorkItem]]]

  def complete(workItem: WorkItem[RetryFileUploadMetadataWorkItem], newStatus: ProcessingStatus with ResultStatus): Future[Boolean]

  def markAs(workItem: WorkItem[RetryFileUploadMetadataWorkItem],
             newStatus: ProcessingStatus with ResultStatus,
             availableAt: DateTime): Future[Boolean]
}

@Singleton
class RetryFileUploadMetadataWorkItemMongoRepo @Inject()(reactiveMongoComponent: ReactiveMongoComponent,
                                                         dateTimeService: DateTimeService,
                                                         declarationsLogger: DeclarationsLogger,
                                                         configuration: Configuration)
                                                        (implicit ec: ExecutionContext)
  extends WorkItemRepository[RetryFileUploadMetadataWorkItem, BSONObjectID] (
    collectionName = "retry-file-uploads-work-item",
    mongo = reactiveMongoComponent.mongoConnector.db,
    itemFormat = RetryFileUploadWorkItemFormat.workItemMongoFormat[RetryFileUploadMetadataWorkItem],
    configuration.underlying) with RetryFileUploadMetadataWorkItemRepo {

  override def workItemFields: WorkItemFieldNames = new WorkItemFieldNames {
    val receivedAt = "createdAt"
    val updatedAt = "lastUpdated"
    val availableAt = "availableAt"
    val status = "status"
    val id = "_id"
    val failureCount = "failures"
  }

  override def now: DateTime = dateTimeService.nowUtc()
  override def inProgressRetryAfterProperty: String = ???
  override lazy val inProgressRetryAfter: Duration = Duration.standardMinutes(1)

  override def saveWithStatus(workItem: RetryFileUploadMetadataWorkItem, processingStatus: ProcessingStatus): Future[WorkItem[RetryFileUploadMetadataWorkItem]] = {
    logger.debug(s"saving a new file upload metadata work item in locked state $workItem")

    def processWithInitialStatus(item: RetryFileUploadMetadataWorkItem): ProcessingStatus = processingStatus
    pushNew(workItem, now, processWithInitialStatus _)
  }

  override def update(csId: SubscriptionFieldsId,
                      reference: FileReference,
                      cf: CallbackFields,
                      processingStatus: ProcessingStatus): Future[Option[WorkItem[RetryFileUploadMetadataWorkItem]]] = {
    logger.debug(s"updating file upload metadata work item keyed by csid ${SubscriptionFieldsId.toString} and file reference: $reference with callbackField=$cf")

    val selector = Json.obj("fileUploadMetadata.fileReference" -> reference.toString, "fileUploadMetadata.csId" -> csId.toString)
    val update = Json.obj("$set" -> Json.obj(workItemFields.status -> processingStatus, "fileUploadMetadata.maybeCallbackFields" ->
      Json.obj("name" -> cf.name,
        "mimeType" -> cf.mimeType,
        "checksum" -> cf.checksum,
        "uploadTimestamp" -> cf.uploadTimestamp,
        "outboundLocation" -> cf.outboundLocation.toString)))

    findAndUpdate(selector, update, fetchNewObject = true).map(_.result[WorkItem[RetryFileUploadMetadataWorkItem]])
  }

  override def complete(workItem: WorkItem[RetryFileUploadMetadataWorkItem], newStatus: ProcessingStatus with ResultStatus): Future[Boolean] = {
    complete(workItem.id, newStatus)
  }

  override def markAs(workItem: WorkItem[RetryFileUploadMetadataWorkItem],
                      newStatus: ProcessingStatus with ResultStatus,
                      availableAt: DateTime): Future[Boolean] = {
    markAs(workItem.id, newStatus, Some(availableAt))
  }
}

object RetryFileUploadWorkItemFormat {

  def workItemMongoFormat[T](implicit fumFormat: Format[T]): Format[WorkItem[T]] =
    ReactiveMongoFormats.mongoEntity(
      fileUploadFormat(ReactiveMongoFormats.objectIdFormats,
        ReactiveMongoFormats.dateTimeFormats,
        fumFormat))

  private def fileUploadFormat[T](implicit bsonIdFormat: Format[BSONObjectID],
                                  dateTimeFormat: Format[DateTime],
                                  fumFormat: Format[T]): Format[WorkItem[T]] = {
    val reads = (
      (__ \ "id").read[BSONObjectID] and
        (__ \ "createdAt").read[DateTime] and
        (__ \ "lastUpdated").read[DateTime] and
        (__ \ "availableAt").read[DateTime] and
        (__ \ "status").read[uk.gov.hmrc.workitem.ProcessingStatus] and
        (__ \ "failures").read[Int].orElse(Reads.pure(0)) and
        (__ \ "fileUploadMetadata").read[T]
      )(WorkItem.apply[T](_, _, _, _, _, _, _))

    val writes = (
      (__ \ "id").write[BSONObjectID] and
        (__ \ "createdAt").write[DateTime] and
        (__ \ "lastUpdated").write[DateTime] and
        (__ \ "availableAt").write[DateTime] and
        (__ \ "status").write[uk.gov.hmrc.workitem.ProcessingStatus] and
        (__ \ "failures").write[Int] and
        (__ \ "fileUploadMetadata").write[T]
      )(unlift(WorkItem.unapply[T]))

    Format(reads, writes)
  }

}
