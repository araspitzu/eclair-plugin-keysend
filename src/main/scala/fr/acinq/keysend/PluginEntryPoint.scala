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

import java.util.UUID

import akka.pattern.ask
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.server.{Route}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.Config
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Crypto, Satoshi}
import fr.acinq.eclair
import fr.acinq.eclair.{CltvExpiryDelta, Kit, MilliSatoshi, Plugin, Setup, ToMilliSatoshiConversion}
import fr.acinq.eclair.api.FormParamExtractors._
import fr.acinq.eclair.api.ExtraDirectives
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentRequest
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.GenericTlv
import grizzled.slf4j.Logging

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class PluginEntryPoint extends Plugin with Logging {

  implicit val timeout = Timeout(30 seconds)
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

    val apiHost = conf.getString("api.binding-ip")
    val apiPort = conf.getInt("api.port") + 1 //FIXME: +1 because we must bind to a different port than the eclair API
    val password = conf.getString("api.password")

    def userPassAuthenticator(credentials: Credentials): Future[Option[String]] = credentials match {
      case p@Credentials.Provided(id) if p.verify(password) => Future.successful(Some(id))
      case _ => akka.pattern.after(1 second, using = kit.system.scheduler)(Future.successful(None))(kit.system.dispatcher) // force a 1 sec pause to deter brute force
    }

    // start keysend api
    val route: Route = {
      new {} with ExtraDirectives {
        val r =
          authenticateBasicAsync(realm = "Access restricted", userPassAuthenticator) { _ =>
            post {
              path("keysend") {
                formFields(amountMsatFormParam, nodeIdFormParam, "finalCltvExpiry".as[Int].?, "maxAttempts".as[Int].?, "feeThresholdSat".as[Satoshi].?, "maxFeePct".as[Double].?, "externalId".?) {
                  (amountMsat, nodeId, finalCltvExpiry_opt, maxAttempts_opt, feeThresholdSat_opt, maxFeePct_opt, externalId_opt) =>
                    complete(doKeysend(amountMsat, nodeId, finalCltvExpiry_opt, maxAttempts_opt, feeThresholdSat_opt, maxFeePct_opt, externalId_opt))
                }
              }
            }
          }
      }
    }.r

    Http().bindAndHandle(route, apiHost, apiPort)
    logger.info(s"ready")
  }

  def doKeysend(amount: MilliSatoshi, nodeId: PublicKey, finalCltvExpiry_opt: Option[Int], maxAttempts_opt: Option[Int], feeThreshold_opt: Option[Satoshi], maxFeePct_opt: Option[Double], externalId_opt: Option[String])(implicit ec: ExecutionContext, timeout: Timeout): Future[String] = {
    val maxAttempts = maxAttempts_opt.getOrElse(kit.nodeParams.maxPaymentAttempts)
    val defaultRouteParams = Router.getDefaultRouteParams(kit.nodeParams.routerConf)
    val routeParams = defaultRouteParams.copy(
      maxFeePct = maxFeePct_opt.getOrElse(defaultRouteParams.maxFeePct),
      maxFeeBase = feeThreshold_opt.map(_.toMilliSatoshi).getOrElse(defaultRouteParams.maxFeeBase)
    )

    val finalCltv = finalCltvExpiry_opt.map(CltvExpiryDelta).getOrElse(Channel.MIN_CLTV_EXPIRY_DELTA)
    val paymentPreimage = eclair.randomBytes32
    val paymentHash = Crypto.sha256(paymentPreimage)
    val keysendTlvRecord = GenericTlv(Keysend.KEYSEND_RECORD_TYPE, paymentPreimage)
    val spr = SendPaymentRequest(amount, paymentHash, nodeId, maxAttempts = maxAttempts, finalExpiryDelta = finalCltv, externalId = externalId_opt, routeParams = Some(routeParams), userCustomTlvs = Seq(keysendTlvRecord))
    (kit.paymentInitiator ? spr).mapTo[UUID].map(_.toString)
  }

}
