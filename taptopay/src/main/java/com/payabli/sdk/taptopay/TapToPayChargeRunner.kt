package com.payabli.sdk.taptopay

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.leavesOutcomeUnknown
import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.model.identifiesSomeone
import com.payabli.sdk.taptopay.network.TTPTransactionClient
import com.payabli.sdk.taptopay.network.TTPTransactionException
import com.payabli.sdk.taptopay.network.sendableAmountOrNull
import com.payabli.sdk.taptopay.provider.CardReadRequest
import com.payabli.sdk.taptopay.provider.CardReadResult
import com.payabli.sdk.taptopay.provider.TapToPayProvider
import com.payabli.sdk.taptopay.session.TapToPaySessionCoordinator
import com.payabli.sdk.taptopay.session.TapToPaySessionManager
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import com.payabli.sdk.taptopay.telemetry.TapToPayReports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/** One payment, end to end: open it at Payabli, tap, close it. */
internal class TapToPayChargeRunner(
    private val entry: String,
    private val coordinator: TapToPaySessionCoordinator,
    private val manager: TapToPaySessionManager,
    private val reader: TapToPayProvider,
    private val client: TTPTransactionClient,
    private val store: AttestedDeviceStore,
    private val keys: ChargeKeyStore,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) {
    /** One payment at a time. A second caller waits; the reader takes one card. */
    private val region = Mutex()

    suspend fun charge(
        paymentDetails: TapToPayPaymentDetails,
        customer: TapToPayCustomerData,
        invoice: TapToPayInvoiceData,
        orderDescription: String?,
    ): TapToPayResult =
        region.withLock {
            val sendable = sendableAmountOf(paymentDetails)
            // The service refuses an opening that identifies nobody, and it refuses it after the reader has
            // been armed and a card taken. Checked here, so a caller learns it before a merchant asks
            // someone to tap.
            require(customer.identifiesSomeone) { "a charge has to name the payer it is for" }

            // After the precondition, so a caller's own bad argument is not counted as a charge that
            // failed. The bracket spans the whole of initiate, the tap and update, because what it
            // measures is what a merchant waits through.
            val startedAt = System.nanoTime()
            TapToPayReports.chargeStarted()

            // Hoisted so the failure path below can name the key this charge sent. Null until it is
            // reserved, which is what says a failure happened before there was an attempt to release.
            var reserved: String? = null
            // Set once the reader has been asked for a card, and never unset. Past that point no failure
            // releases the key: the sale may already be captured, so nothing arriving afterwards is
            // evidence the money did not move.
            var askedForCard = false
            try {
                // Repairs a spent reader session and does nothing to a ready one.
                coordinator.reinitializeIfNeeded()
                check(manager.state.value == TapToPaySessionState.Ready) { "the terminal is not ready" }

                // A ready session with no stored device means the record was lost after it came up, which
                // `AttestedDeviceStore.read` reports by answering null. Expire the session before throwing,
                // or `isReady` stays true and every retry reaches this same line.
                val deviceId =
                    store.read(entry)?.deviceId ?: run {
                        manager.invalidate()
                        error("the session is ready with no device to charge as")
                    }
                // Reserved after the checks above, so a charge that never reaches the wire leaves no key
                // behind, and held across a failure that leaves it unknown whether this opened anything.
                val idempotencyKey = keys.reserve(entry)
                reserved = idempotencyKey
                val paymentTransId =
                    client.initiate(
                        entryPoint = entry,
                        deviceId = deviceId,
                        paymentDetails = paymentDetails,
                        idempotencyKey = idempotencyKey,
                        customer = customer,
                        invoice = invoice,
                        orderDescription = orderDescription,
                    )
                logger.debug(
                    LogField.safe("event", "ttp_charge_opened"),
                    LogField.safe("phase", "initiate"),
                ) { "the payment was opened" }

                // Set before the reader is asked, not after it answers: the processor takes the sale before
                // the answer is delivered, so everything from here on may have moved money.
                askedForCard = true
                val result = readCard(paymentTransId, sendable, invoice)

                // Uncancellable, for the same reason the failed-read close is: once `startReading` has
                // returned, the processor has taken the card, and this is the only call that tells the
                // service so. A cancellation arriving here would unwind through the withdrawn branch and
                // leave a processed charge open, while the caller is told it withdrew and may charge again.
                // The transport's own deadlines still bound it, so this cannot wait forever.
                //
                // The settle is inside for the same reason rather than a tidier one: a cancellation landing
                // between the two leaves the attempt unsettled, so the next charge reuses a key the service
                // has already seen and is refused as a duplicate. Closing and finishing the attempt are one
                // step or neither.
                withContext(NonCancellable) {
                    client.update(paymentTransId, result)
                    // The close landed, so this transaction is resolved and its attempt is over.
                    keys.settle(entry, idempotencyKey)
                }
                TapToPayResult(paymentTransId = paymentTransId, cardNetwork = result.cardNetwork)
                    .also { TapToPayReports.chargeSucceeded(startedAt) }
            } catch (withdrawn: CancellationException) {
                // `Throwable` covers CancellationException, and the facade states a withdrawn caller is
                // not a failure.
                throw withdrawn
            } catch (failure: Throwable) {
                // Only before the reader answered, and only for a failure that says nothing was opened.
                // After the reader has answered the sale may be captured, so no failure arriving from
                // there on is evidence the money did not move.
                if (!askedForCard && isAnswered(failure)) reserved?.let { keys.settle(entry, it) }
                TapToPayReports.chargeFailed(failure, startedAt, cardWasAsked = askedForCard)
                throw failure
            }
        }

    /**
     * Asks the reader for one card, and closes [paymentTransId] if it does not deliver one.
     *
     * [amount] is the value at the scale the paypoint recorded, so the card is asked for what was opened.
     *
     * **Every exit but a card leaves a transaction open at the service**, cancellation and a JVM `Error`
     * included, so every failure branch closes it on the way out. The `Error` is rethrown unchanged rather
     * than converted, so the facade's promise that one is not caught still holds for a caller: what the
     * branch exists for is the open payment it would otherwise leave standing.
     *
     * The key is never settled here. The reader has been asked by this point, so the sale may be captured
     * and nothing arriving afterwards says the money did not move.
     */
    private suspend fun readCard(
        paymentTransId: String,
        amount: BigDecimal,
        invoice: TapToPayInvoiceData,
    ): CardReadResult =
        try {
            reader.startReading(
                CardReadRequest(
                    amount = amount,
                    merchantTransactionId = paymentTransId,
                    merchantOrderId = paymentTransId,
                    merchantInvoiceNumber = invoice.invoiceNumber,
                ),
            )
        } catch (withdrawn: CancellationException) {
            closeAfterFailedRead(paymentTransId, withdrawn)
            throw withdrawn
        } catch (failure: Throwable) {
            // A spent reader session is repaired by re-initializing. `invalidate` drops the move when the
            // state has already left ready, so a failure arriving after a replacement is built does not
            // kill the healthy session.
            //
            // A denial expires it too: Ready may only move to SessionExpired, so the DEVICE_INELIGIBLE
            // landing is unreachable here and the repair lands it.
            if (failure is CardReaderException.SessionUnusable ||
                failure is CardReaderException.DeviceDenied
            ) {
                manager.invalidate()
            }
            closeAfterFailedRead(paymentTransId, failure)
            throw failure
        }

    /**
     * The amount [paymentDetails] will actually send, once both of its values have been checked.
     *
     * **Checked at the scale it will be sent at.** `0.001` is above zero and reaches the wire as `0.00`, so
     * the raw value passing says nothing about what the paypoint records. The service fee takes the same
     * rounding through the same serializer; zero is allowed there and below zero is not.
     */
    private fun sendableAmountOf(paymentDetails: TapToPayPaymentDetails): BigDecimal {
        val sendable =
            requireNotNull(paymentDetails.amount.sendableAmountOrNull()) {
                "an amount has to be one this SDK can send"
            }
        require(sendable > BigDecimal.ZERO) { "an amount has to be greater than zero" }

        val sendableFee =
            requireNotNull(paymentDetails.serviceFee.sendableAmountOrNull()) {
                "a service fee has to be one this SDK can send"
            }
        require(sendableFee >= BigDecimal.ZERO) { "a service fee cannot be negative" }
        return sendable
    }

    /**
     * Closes a transaction whose tap did not complete, best effort. The attempt stays named either way.
     *
     * Uncancellable: a withdrawn caller is one of the ways a tap does not complete, and the transaction is
     * open either way.
     *
     * **The key is never released here, and a recorded close is not a recorded failure.** The close makes
     * the service pull the processor for this transaction and write back what it finds, so a sale the
     * processor captured comes back captured rather than failed. This call sends the request and does not
     * decode the answer, so a close that landed says the service now knows the outcome and not what the
     * outcome was. Releasing on it would rotate the key after a capture and let the next charge take the
     * money again. Holding it keeps the repeat named as one attempt, which is the whole point of the key.
     */
    private suspend fun closeAfterFailedRead(
        paymentTransId: String,
        failure: Throwable,
    ) = withContext(NonCancellable) {
        try {
            client.updateAfterFailedRead(paymentTransId, failure.javaClass.simpleName)
        } catch (failedClose: Exception) {
            logger.warn(
                LogField.safe("event", "ttp_charge_close_failed"),
                LogField.safe("phase", "update"),
                LogField.safe("errorKind", failedClose.javaClass.simpleName),
            ) { "an opened payment could not be closed after a failed tap" }
        }
    }

    /**
     * Whether [failure] says nothing was opened, so its key can be let go.
     *
     * Read only before the reader has answered. The service refusing, declining or reporting the paypoint
     * unequipped are all answers about the opening: no transaction exists, so what comes next is a new
     * attempt and a held key would refuse it. Anything else is kept.
     *
     * Kept rather than released is the safe direction, so this answers true only for what it recognises.
     */
    private fun isAnswered(failure: Throwable): Boolean =
        when (failure) {
            is CancellationException -> false
            is TTPTransactionException.Refused,
            is TTPTransactionException.ServiceRejected,
            is TTPTransactionException.NotEnabled,
            -> true

            is PayabliException -> !failure.code.leavesOutcomeUnknown
            else -> false
        }
}
