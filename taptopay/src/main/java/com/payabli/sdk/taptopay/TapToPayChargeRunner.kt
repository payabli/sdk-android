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

    /**
     * A payment the card was charged for and that could not be closed, held so it can be closed later.
     *
     * Read and written under [region], so there is never more than one. [CardReadResult] carries the card's
     * expiry and the token the processor minted, so it is dropped as soon as the close lands and again
     * whenever a new payment is opened.
     */
    private var pendingClose: PendingClose? = null

    private class PendingClose(
        val paymentTransId: String,
        val read: CardReadResult,
    )

    suspend fun charge(
        paymentDetails: TapToPayPaymentDetails,
        customer: TapToPayCustomerData,
        invoice: TapToPayInvoiceData,
        orderDescription: String?,
    ): TapToPayResult =
        region.withLock {
            // At the scale it will be sent at, not as supplied. `0.001` is more than zero and reaches the
            // wire as `0.00`, so checking the raw value opened a payment for nothing.
            val amount = paymentDetails.amount
            val sendable =
                requireNotNull(amount.sendableAmountOrNull()) { "an amount has to be one this SDK can send" }
            require(sendable > BigDecimal.ZERO) { "an amount has to be greater than zero" }

            // Same checks, same serializer. Zero is allowed; below zero is not.
            val sendableFee =
                requireNotNull(paymentDetails.serviceFee.sendableAmountOrNull()) {
                    "a service fee has to be one this SDK can send"
                }
            require(sendableFee >= BigDecimal.ZERO) { "a service fee cannot be negative" }

            // After the precondition, so a caller's own bad argument is not counted as a charge that
            // failed. The bracket spans the whole of initiate, the tap and update, because what it
            // measures is what a merchant waits through.
            val startedAt = System.nanoTime()
            // What the failure will be able to say, which only this scope knows.
            var openedAs: String? = null
            var captured = false
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
                openedAs = paymentTransId
                // A second payment now exists, so the one held from a failed close is no longer the one a
                // caller means.
                pendingClose = null
                logger.debug(
                    LogField.safe("event", "ttp_charge_opened"),
                    LogField.safe("phase", "initiate"),
                ) { "the payment was opened" }

                // Set before the reader is asked, not after it answers: the processor takes the sale before
                // the answer is delivered, so everything from here on may have moved money.
                askedForCard = true
                val result =
                    try {
                        reader.startReading(
                            CardReadRequest(
                                // The rounded value, so the card is asked for what the paypoint recorded.
                                amount = sendable,
                                merchantTransactionId = paymentTransId,
                                merchantOrderId = paymentTransId,
                                merchantInvoiceNumber = invoice.invoiceNumber,
                            ),
                        )
                    } catch (withdrawn: CancellationException) {
                        closeAfterFailedRead(paymentTransId, withdrawn, idempotencyKey)
                        throw withdrawn
                    } catch (failure: Exception) {
                        // A spent reader session is repaired by re-initializing. `invalidate` drops the move when
                        // the state has already left ready, so a failure arriving after a replacement is built
                        // does not kill the healthy session.
                        //
                        // A denial expires it too: Ready may only move to SessionExpired, so the
                        // DEVICE_INELIGIBLE landing is unreachable here and the repair lands it.
                        if (failure is CardReaderException.SessionUnusable ||
                            failure is CardReaderException.DeviceDenied
                        ) {
                            manager.invalidate()
                        }
                        closeAfterFailedRead(paymentTransId, failure, idempotencyKey)
                        throw failure
                    }

                // The card has been charged. Everything from here reports a payment whose money has moved.
                captured = true
                pendingClose = PendingClose(paymentTransId, result)

                // Uncancellable, for the same reason the failed-read close is: once `startReading` has
                // returned, the processor has taken the card, and this is the only call that tells the
                // service so. A cancellation arriving here would unwind through the withdrawn branch and
                // leave a processed charge open, while the caller is told it withdrew and may charge again.
                // The transport's own deadlines still bound it, so this cannot wait forever.
                //
                // The settle is inside for the same reason rather than a tidier one: a cancellation landing
                // between the two leaves the attempt unsettled, so the next charge reuses a key the service
                // has already seen and is refused as a duplicate. So is dropping the held payment, which
                // would otherwise be offered for closing again after it had closed. Those three are one step
                // or none.
                withContext(NonCancellable) {
                    client.update(paymentTransId, result)
                    // The close landed, so this transaction is resolved and its attempt is over.
                    keys.settle(entry, idempotencyKey)
                    pendingClose = null
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
                // Reported before it is wrapped: the report reads the failure's own type to decide what kind
                // of failure it was, and would classify every one of them alike once wrapped.
                TapToPayReports.chargeFailed(failure, startedAt)
                // An Error is left as it is, as the facade leaves it: a linkage error is not a payment
                // outcome and has no transaction to name.
                throw if (failure is Exception) failed(failure, openedAs, captured) else failure
            }
        }

    /**
     * Closes a payment the card was charged for, when the close did not land at the time.
     *
     * Takes no second tap: the reader already answered and its answer was kept. Refuses anything but the
     * payment currently held, so a mistyped identifier cannot close a payment this SDK has no answer for.
     */
    suspend fun closeCaptured(paymentTransId: String): Unit =
        region.withLock {
            val pending = pendingClose
            require(pending != null && pending.paymentTransId == paymentTransId) {
                "no captured payment is waiting to be closed under that identifier"
            }
            val startedAt = System.nanoTime()
            TapToPayReports.closeStarted()
            try {
                settle(pending.paymentTransId, pending.read)
            } catch (withdrawn: CancellationException) {
                // Converting it would hide it from the facade, which reads the type to decide what to
                // rethrow.
                throw withdrawn
            } catch (failure: Exception) {
                TapToPayReports.closeFailed(failure, startedAt)
                // Still held, so this can be tried again.
                throw failed(failure, pending.paymentTransId, captured = true)
            }
            pendingClose = null
            TapToPayReports.closeSucceeded(startedAt)
        }

    /**
     * Closes a payment the card was already charged for.
     *
     * Uncancellable, for the reason [closeAfterFailedRead] is: the money has moved either way, and a caller
     * withdrawing mid-close would otherwise leave the payment open with nothing holding the answer. The
     * cancellation still unwinds once the close is done.
     */
    private suspend fun settle(
        paymentTransId: String,
        read: CardReadResult,
    ) = withContext(NonCancellable) { client.update(paymentTransId, read) }

    /** The failure a caller sees, carrying the payment it belongs to and whether the money moved. */
    private fun failed(
        failure: Exception,
        paymentTransId: String?,
        captured: Boolean,
    ) = TapToPayException(
        failure.message ?: failure.javaClass.simpleName,
        failure,
        paymentTransId = paymentTransId,
        captured = captured,
    )

    /**
     * Closes a transaction whose tap did not complete, best effort, and lets its key go if that lands.
     *
     * Uncancellable: a withdrawn caller is one of the ways a tap does not complete, and the transaction is
     * open either way. That also makes it the one place a cancelled charge can still reach storage.
     *
     * **The key is released only if the close is recorded.** A read that failed is not proof that nothing
     * was captured — the processor takes the sale before the answer reaches this code, so a cancellation
     * can race with delivery. What the close settles is the transaction: once the service has recorded this
     * one as failed, its outcome is no longer in doubt and the next sale needs its own. When the close does
     * not land, the transaction is open and the attempt stays named, so a repeat is recognizable as one.
     */
    private suspend fun closeAfterFailedRead(
        paymentTransId: String,
        failure: Throwable,
        idempotencyKey: String,
    ) = withContext(NonCancellable) {
        try {
            client.updateAfterFailedRead(paymentTransId, failure.javaClass.simpleName)
            keys.settle(entry, idempotencyKey)
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
