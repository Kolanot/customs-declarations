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

package uk.gov.hmrc.customs.declaration.config

import java.time.Clock

import com.google.inject.AbstractModule
import uk.gov.hmrc.customs.declaration.services.DeclarationsConfigService
import uk.gov.hmrc.customs.declaration.services.filetransmission.TransmissionRequestProcessingJob
import uk.gov.hmrc.customs.declaration.services.filetransmission.queue.{MongoBackedWorkItemService, QueueJob, WorkItemProcessingScheduler, WorkItemService}

class CustomsDeclarationsModule extends AbstractModule {

  def configure() {
    // asEagerSingleton forces evaluation at application startup time
    bind(classOf[DeclarationsConfigService]).asEagerSingleton()
    bind(classOf[QueueJob]).to(classOf[TransmissionRequestProcessingJob])
    bind(classOf[WorkItemProcessingScheduler]).asEagerSingleton()
    bind(classOf[WorkItemService]).to(classOf[MongoBackedWorkItemService])
    bind(classOf[Clock]).toInstance(Clock.systemUTC())
  }
}
