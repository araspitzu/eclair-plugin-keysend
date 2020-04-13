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

import akka.actor.Actor.Receive
import akka.actor.{ActorContext, ActorRef}
import akka.event.{DiagnosticLoggingAdapter, LoggingAdapter}
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, Channel}
import fr.acinq.eclair.db.PaymentsDb
import fr.acinq.eclair.{CltvExpiry, Logs, MilliSatoshi, NodeParams}
import fr.acinq.eclair.payment.{IncomingPacket, PaymentRequest}
import fr.acinq.eclair.payment.IncomingPacket.FinalPacket
import fr.acinq.eclair.payment.receive.ReceiveHandler
import fr.acinq.eclair.payment.relay.CommandBuffer
import fr.acinq.eclair.wire.Onion.FinalTlvPayload
import fr.acinq.eclair.wire.IncorrectOrUnknownPaymentDetails

class KeySendPaymentHandler(nodeParams: NodeParams, cmdBuffer: ActorRef) extends ReceiveHandler {

  val db: PaymentsDb = nodeParams.db.payments

  override def handle(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Receive = {
    case packet@FinalPacket(htlc, payload:FinalTlvPayload) if isKeysendPayload(payload) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(htlc.paymentHash))) {
        val Some(keysendRecord) = payload.records.unknown.find(_.tag == Keysend.KEYSEND_RECORD_TYPE)
        val preimage = ByteVector32(keysendRecord.value)
        validatePayment(packet, preimage) match {
          case false =>
            cmdBuffer ! CMD_FAIL_HTLC(htlc.id, Right(IncorrectOrUnknownPaymentDetails(payload.totalAmount, nodeParams.currentBlockHeight)), commit = true)
          case true =>
            log.info(s"received keysend payment")
            cmdBuffer ! CommandBuffer.CommandSend(htlc.channelId, CMD_FULFILL_HTLC(htlc.id, preimage, commit = true))
            val fakeRequest = PaymentRequest(nodeParams.chainHash, Some(htlc.amountMsat), htlc.paymentHash, nodeParams.privateKey, "Fake invoice for keysend incoming payment")
            db.addIncomingPayment(fakeRequest, preimage, paymentType = "KeySend")
            db.receiveIncomingPayment(htlc.paymentHash, htlc.amountMsat)
        }
      }
  }

  def isKeysendPayload(payload: FinalTlvPayload): Boolean = {
    payload.records.unknown.exists(_.tag == Keysend.KEYSEND_RECORD_TYPE)
  }

  def validatePayment(payload: FinalPacket, preimage: ByteVector32)(implicit log: LoggingAdapter): Boolean = {
    validatePaymentCltv(payload, Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(nodeParams.currentBlockHeight)) &&
    validatePaymentAmount(payload, payload.payload.totalAmount) &&
    Crypto.sha256(preimage) === payload.add.paymentHash // check if the preimage we've received actually redeems the htlc
  }

  private def validatePaymentAmount(payment: IncomingPacket.FinalPacket, expectedAmount: MilliSatoshi)(implicit log: LoggingAdapter): Boolean = {
    // The total amount must be equal or greater than the requested amount. A slight overpaying is permitted, however
    // it must not be greater than two times the requested amount.
    // see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#failure-messages
    if (payment.payload.totalAmount < expectedAmount) {
      log.warning(s"received payment with amount too small for amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else if (payment.payload.totalAmount > expectedAmount * 2) {
      log.warning(s"received payment with amount too large for amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else {
      true
    }
  }

  private def validatePaymentCltv(payment: FinalPacket, minExpiry: CltvExpiry)(implicit log: LoggingAdapter): Boolean = {
    if (payment.add.cltvExpiry < minExpiry) {
      log.warning(s"received payment with expiry too small for amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else {
      true
    }
  }

}
