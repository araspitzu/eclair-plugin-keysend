package fr.acinq.keysend.api

import java.util.UUID

import akka.pattern.ask
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.Credentials
import akka.util.Timeout
import com.typesafe.config.Config
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Crypto, Satoshi}
import fr.acinq.eclair
import fr.acinq.eclair.api.FormParamExtractors._
import fr.acinq.eclair.{CltvExpiryDelta, Kit, MilliSatoshi, ToMilliSatoshiConversion}
import fr.acinq.eclair.api.ExtraDirectives
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentRequest
import fr.acinq.eclair.router.RouteCalculation
import fr.acinq.eclair.wire.GenericTlv
import fr.acinq.keysend.Keysend

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class Service(conf: Config, kit: Kit) extends ExtraDirectives {

  val system = kit.system
  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(30 seconds)

  val password = conf.getString("api.password")
  val apiHost = conf.getString("api.binding-ip")
  val apiPort = {
    if(conf.hasPath("keysend.api.port"))
      conf.getInt("keysend.api.port")
    else
      conf.getInt("api.port") + 1 // if no port was specified we default to eclair api port + 1
  }

  def userPassAuthenticator(credentials: Credentials): Future[Option[String]] = credentials match {
    case p@Credentials.Provided(id) if p.verify(password) => Future.successful(Some(id))
    case _ => akka.pattern.after(1 second, using = system.scheduler)(Future.successful(None))(system.dispatcher) // force a 1 sec pause to deter brute force
  }

  val route: Route = authenticateBasicAsync(realm = "Access restricted", userPassAuthenticator) { _ =>
    post {
      path("keysend") {
        formFields(amountMsatFormParam, nodeIdFormParam, "finalCltvExpiry".as[Int].?, "maxAttempts".as[Int].?, "feeThresholdSat".as[Satoshi].?, "maxFeePct".as[Double].?, "externalId".?) {
          (amountMsat, nodeId, finalCltvExpiry_opt, maxAttempts_opt, feeThresholdSat_opt, maxFeePct_opt, externalId_opt) =>
            complete(doKeysend(amountMsat, nodeId, finalCltvExpiry_opt, maxAttempts_opt, feeThresholdSat_opt, maxFeePct_opt, externalId_opt))
        }
      }
    }
  }

  def doKeysend(amount: MilliSatoshi, nodeId: PublicKey, finalCltvExpiry_opt: Option[Int], maxAttempts_opt: Option[Int], feeThreshold_opt: Option[Satoshi], maxFeePct_opt: Option[Double], externalId_opt: Option[String])(implicit ec: ExecutionContext, timeout: Timeout): Future[String] = {
    val maxAttempts = maxAttempts_opt.getOrElse(kit.nodeParams.maxPaymentAttempts)
    val defaultRouteParams = RouteCalculation.getDefaultRouteParams(kit.nodeParams.routerConf)
    val routeParams = defaultRouteParams.copy(
      maxFeePct = maxFeePct_opt.getOrElse(defaultRouteParams.maxFeePct),
      maxFeeBase = feeThreshold_opt.map(_.toMilliSatoshi).getOrElse(defaultRouteParams.maxFeeBase)
    )

    val finalCltv = finalCltvExpiry_opt.map(CltvExpiryDelta).getOrElse(Channel.MIN_CLTV_EXPIRY_DELTA + 1)
    val paymentPreimage = eclair.randomBytes32
    val paymentHash = Crypto.sha256(paymentPreimage)
    val keysendTlvRecord = GenericTlv(Keysend.KEYSEND_RECORD_TYPE, paymentPreimage)
    val spr = SendPaymentRequest(amount, paymentHash, nodeId, maxAttempts = maxAttempts, finalExpiryDelta = finalCltv, externalId = externalId_opt, routeParams = Some(routeParams), userCustomTlvs = Seq(keysendTlvRecord))
    (kit.paymentInitiator ? spr).mapTo[UUID].map(_.toString)
  }

}
