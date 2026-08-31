package com.payabli.sdk.taptopay

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.network.TTPTransactionClient
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
            val amount = paymentDetails.amount
            require(amount > BigDecimal.ZERO) { "an amount has to be greater than zero" }

            // After the precondition, so a caller's own bad argument is not counted as a charge that
            // failed. The bracket spans the whole of initiate, the tap and update, because what it
            // measures is what a merchant waits through.
            val startedAt = System.nanoTime()
            TapToPayReports.chargeStarted()
            try {
                // Repairs a spent reader session and does nothing to a ready one.
                coordinator.reinitializeIfNeeded()
                check(manager.state.value == TapToPaySessionState.Ready) { "the terminal is not ready" }

                val deviceId =
                    store.read(entry)?.deviceId ?: error("the session is ready with no device to charge as")
                val paymentTransId =
                    client.initiate(
                        entryPoint = entry,
                        deviceId = deviceId,
                        paymentDetails = paymentDetails,
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
                                amount = amount,
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
                        if (failure is CardReaderException.SessionUnusable) manager.invalidate()
                        closeAfterFailedRead(paymentTransId, failure)
                        throw failure
                    }

                client.update(paymentTransId, result)
                TapToPayResult(paymentTransId = paymentTransId, cardNetwork = result.cardNetwork)
                    .also { TapToPayReports.chargeSucceeded(startedAt) }
            } catch (failure: Throwable) {
                TapToPayReports.chargeFailed(failure, startedAt)
                throw failure
            }
        }

    /**
     * Closes a transaction whose tap never completed, best effort.
     *
     * Uncancellable: a withdrawn caller is one of the ways a tap does not complete, and the transaction is
     * open either way.
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
}
