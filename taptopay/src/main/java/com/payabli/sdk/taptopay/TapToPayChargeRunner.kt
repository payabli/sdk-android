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
            TapToPayReports.chargeStarted()
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
                        closeAfterFailedRead(paymentTransId, withdrawn)
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
                        closeAfterFailedRead(paymentTransId, failure)
                        throw failure
                    }

                client.update(paymentTransId, result)
                keys.settle(entry)
                TapToPayResult(paymentTransId = paymentTransId, cardNetwork = result.cardNetwork)
                    .also { TapToPayReports.chargeSucceeded(startedAt) }
            } catch (withdrawn: CancellationException) {
                // `Throwable` covers CancellationException, and the facade states a withdrawn caller is
                // not a failure.
                throw withdrawn
            } catch (failure: Throwable) {
                if (isAnswered(failure)) keys.settle(entry)
                TapToPayReports.chargeFailed(failure, startedAt)
                throw failure
            }
        }

    /**
     * Closes a transaction whose tap never completed, best effort, and lets its key go.
     *
     * Uncancellable: a withdrawn caller is one of the ways a tap does not complete, and the transaction is
     * open either way. That also makes it the one place a cancelled charge can still reach storage, which is
     * why the key is released here rather than beside the caller's own failure handling.
     *
     * **The key is released whether or not the close lands.** No card was read, so nothing was captured and
     * the payer has not paid; what comes next is a new sale needing its own transaction, and holding the key
     * would suppress it. An opened transaction left standing is the separate cost, and closing it is what
     * this call already attempts.
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
        keys.settle(entry)
    }

    /**
     * Whether [failure] says what became of the charge, so its key can be let go.
     *
     * A key is held while a repeat might be a repeat. The service refusing, declining or reporting the
     * paypoint unequipped are all answers: nothing was opened, so what comes next is a new attempt and a
     * held key would refuse it. Anything else is kept, including a failure of the closing call, where the
     * reader has already captured the sale and a fresh key is what turns the obvious retry into a second
     * charge.
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
