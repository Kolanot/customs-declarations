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

package uk.gov.hmrc.customs.declaration.services

import java.net.URL

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.customs.declaration.logging.DeclarationsLogger
import uk.gov.hmrc.customs.declaration.model._
import uk.gov.hmrc.customs.declaration.model.actionbuilders.HasConversationId
import uk.gov.hmrc.customs.declaration.repo.BatchFileUploadMetadataRepo
import uk.gov.hmrc.customs.declaration.services.filetransmission.queue.WorkItemService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class BatchFileUploadUpscanNotificationBusinessService @Inject()(repo: BatchFileUploadMetadataRepo,
                                                                 workItemService: WorkItemService,
                                                                 config: DeclarationsConfigService,
                                                                 logger: DeclarationsLogger) {

  def persistAndCallWorkItemService(ready: UploadedReadyCallbackBody)(implicit r: HasConversationId): Future[Unit] = {
    repo.update(
      ready.reference,
      CallbackFields(ready.uploadDetails.fileName, ready.uploadDetails.fileMimeType, ready.uploadDetails.checksum)
    ).flatMap{
      case None =>
        val errorMsg = s"database error - can't find record with file reference ${ready.reference}"
        logger.error(errorMsg)
        Future.failed(new IllegalStateException(errorMsg))
      case Some(metadata) =>
        maybeFileTransmission(ready.reference, metadata) match {
          case None =>
            val errorMsg = s"database error - can't find file with file reference ${ready.reference}"
            logger.error(errorMsg)
            Future.failed(new IllegalStateException(errorMsg))
          case Some(fileTransmission) =>
            val envelope = FileTransmissionEnvelope(fileTransmission,Whatever(deliveryWindowDuration = None))
            workItemService.enqueue(envelope).map { _ =>
              logger.info(s"successfully called work item service $fileTransmission")
              ()
            }
      }
    }
  }

  //MC POC only
  def storeWorkItemAndProcessLater(ftr: FileTransmission)(implicit r: HasConversationId): Future[Unit] = {
    val envelope = FileTransmissionEnvelope(ftr, Whatever(deliveryWindowDuration = None))
    workItemService.enqueue(envelope).map { _ =>
      logger.debugWithoutRequestContext(s"POC => successfully stored work item $ftr")
      ()
    }
  }

  //PB POC only
  def storeWorkItemAndProcessNow(ftr: FileTransmission)(implicit r: HasConversationId): Future[Unit] = {
    val envelope = FileTransmissionEnvelope(ftr, Whatever(deliveryWindowDuration = None))
    workItemService.enqueueAndProcess(envelope).map { _ =>
      logger.debugWithoutRequestContext(s"POC => successfully stored and processed work item $ftr")
      ()
    }
  }

  private def maybeFileTransmission(fileReference: FileReference, md: BatchFileUploadMetadata): Option[FileTransmission] = {
    for {
      (bf, ftf) <- maybeFileTransmissionFile(fileReference, md)
    } yield FileTransmission(
      FileTransmissionBatch(md.batchId, md.fileCount),
      new URL(config.batchFileUploadConfig.fileTransmissionBaseUrl),
      ftf,
      FileTransmissionInterface("DEC64", "1.0.0"),
      Seq("DeclarationId" -> md.declarationId.toString, "Eori" -> md.eori.toString, "ContentType" -> bf.documentType.toString).map(t => FileTransmissionProperty(name = t._1, value = t._2))
    )
  }

  private def maybeFileTransmissionFile(fileReference: FileReference, metadata: BatchFileUploadMetadata): Option[(BatchFile, FileTransmissionFile)] = {
    for {
      batchFile <- metadata.files.find(bf => bf.reference == fileReference)
      cbFields <- batchFile.maybeCallbackFields
    } yield (
      batchFile,
      FileTransmissionFile(
        batchFile.reference,
        cbFields.name,
        cbFields.mimeType,
        cbFields.checksum,
        batchFile.location,
        batchFile.sequenceNumber
      )
    )
  }

}
