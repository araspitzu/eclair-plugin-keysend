/*
 * Copyright 2020 ACINQ SAS
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
package fr.acinq.keysend

import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import fr.acinq.eclair.{Kit, Plugin, Setup}
import fr.acinq.keysend.api.Service
import grizzled.slf4j.Logging

class KeysendPlugin extends Plugin with Logging {

  var conf: Config = null
  var kit: Kit = null

  override def onSetup(setup: Setup): Unit = {
    conf = setup.config
  }

  override def onKit(kit: Kit): Unit = {
    this.kit = kit
    implicit val system = kit.system
    implicit val ec = kit.system.dispatcher
    implicit val materializer = ActorMaterializer()

    val keySendPaymentHandler = new KeySendPaymentHandler(kit.nodeParams, kit.commandBuffer)
    kit.paymentHandler ! keySendPaymentHandler

    val apiService = new Service(conf, kit)

    Http().bindAndHandle(apiService.route, apiService.apiHost, apiService.apiPort)
    logger.info(s"ready")
  }


}
