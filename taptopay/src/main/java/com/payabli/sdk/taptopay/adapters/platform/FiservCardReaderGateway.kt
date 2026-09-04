package com.payabli.sdk.taptopay.adapters.platform

import android.content.Context
import com.fiserv.commercehub.ttp.provider.FiservTTPCardReader
import com.fiserv.commercehub.ttp.provider.constants.Currency
import com.fiserv.commercehub.ttp.provider.constants.Environment
import com.fiserv.commercehub.ttp.provider.constants.PaymentTransactionType
import com.fiserv.commercehub.ttp.provider.exception.FSSDKInternalSDKUninitializedException
import com.fiserv.commercehub.ttp.provider.exception.FSSDKNFCDisabledInternalException
import com.fiserv.commercehub.ttp.provider.exception.FSSDKNFCDisabledSDKException
import com.fiserv.commercehub.ttp.provider.exception.FSSDKNFCNotSupportSDKException
import com.fiserv.commercehub.ttp.provider.exception.FSSDKNFCNotSupportedInternalException
import com.fiserv.commercehub.ttp.provider.exception.FiservTTPCardReaderException
import com.fiserv.commercehub.ttp.provider.model.ChargesResponse
import com.fiserv.commercehub.ttp.provider.model.FiservTTPConfig
import com.fiserv.commercehub.ttp.provider.model.TransactionDetailsRequest
import com.payabli.sdk.taptopay.adapters.CardReaderFailure
import com.payabli.sdk.taptopay.adapters.CardReaderGateway
import com.payabli.sdk.taptopay.adapters.ChargeRecord
import com.payabli.sdk.taptopay.adapters.GatewayResponseRecord
import com.payabli.sdk.taptopay.adapters.PaymentReceiptRecord
import com.payabli.sdk.taptopay.adapters.ProcessorResponseRecord
import com.payabli.sdk.taptopay.adapters.ReaderArming
import com.payabli.sdk.taptopay.adapters.ReaderCharge
import com.payabli.sdk.taptopay.adapters.ReaderCurrency
import com.payabli.sdk.taptopay.adapters.ReaderEnvironment
import com.payabli.sdk.taptopay.adapters.ReaderFailureKind
import com.payabli.sdk.taptopay.adapters.TransactionProcessingRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The gateway against the real card reader, and all of the vendor surface this SDK has.
 *
 * The application context, never an Activity: the reader outlives any of them.
 *
 * **Both calls run on [dispatcher], never on the caller's thread.** Arming does not return when it is
 * called on the main thread, and a caller reaching this from a view-model's scope is the ordinary case.
 */
internal class FiservCardReaderGateway(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CardReaderGateway {
    private val context = context.applicationContext

    override suspend fun prepareReader(config: ReaderArming) {
        withContext(dispatcher) {
            mappingFailures {
                FiservTTPCardReader
                    .initializeSession(context, config.toVendorConfig())
                    .first()
                    .getOrThrow()
            }
        }
    }

    override suspend fun startReading(request: ReaderCharge): ChargeRecord =
        withContext(dispatcher) {
            mappingFailures {
                FiservTTPCardReader
                    .charges(
                        request.amount,
                        PaymentTransactionType.SALE,
                        TransactionDetailsRequest(
                            // A sale is captured as it is authorized. The reader refuses one that is not.
                            true,
                            request.merchantTransactionId,
                            request.merchantOrderId,
                            // Nothing here stores an instrument. A token is the card-not-present path's to mint.
                            false,
                        ),
                        null,
                        null,
                    ).first()
                    .getOrThrow()
                    .toRecord()
            }
        }
}

/** Internal so the diagnostic tier arms the reader through the same mapping the SDK ships. */
internal fun ReaderArming.toVendorConfig(): FiservTTPConfig =
    FiservTTPConfig(
        merchantId = merchantId,
        terminalId = terminalId,
        apiKey = apiKey,
        secretKey = secretKey,
        ppid = ppId,
        hostPort = hostPort,
        environment = environment.toVendorEnvironment(),
        currencyCode = currency.toVendorCurrency(),
    )

private fun ReaderEnvironment.toVendorEnvironment(): Environment =
    when (this) {
        ReaderEnvironment.DEV -> Environment.DEV
        ReaderEnvironment.QA -> Environment.QA
        ReaderEnvironment.INT -> Environment.INT
        ReaderEnvironment.CAT -> Environment.CAT
        ReaderEnvironment.CERT -> Environment.CERT
        ReaderEnvironment.PERF -> Environment.PERF
        ReaderEnvironment.PROD -> Environment.PROD
    }

private fun ReaderCurrency.toVendorCurrency(): Currency =
    when (this) {
        ReaderCurrency.USD -> Currency.USD
        ReaderCurrency.AUD -> Currency.AUD
    }

/** A list of fields, so the card the same object carries cannot travel with them. */
private fun ChargesResponse.toRecord(): ChargeRecord =
    ChargeRecord(
        gatewayResponse =
            gatewayResponse?.let { response ->
                GatewayResponseRecord(
                    transactionType = response.transactionType,
                    transactionState = response.transactionState,
                    transactionOrigin = response.transactionOrigin,
                    gatewayTransactionId = response.gatewayTransactionId,
                    gatewayName = response.gatewayName,
                    gatewayOrderId = response.gatewayOrderId,
                    transactionProcessingDetails =
                        response.transactionProcessingDetails?.let { details ->
                            TransactionProcessingRecord(
                                orderId = details.orderId,
                                transactionTimestamp = details.transactionTimestamp,
                                apiTraceId = details.apiTraceId,
                                clientRequestId = details.clientRequestId,
                                transactionId = details.transactionId,
                            )
                        },
                )
            },
        paymentReceipt =
            paymentReceipt?.processorResponseDetails?.let { processor ->
                PaymentReceiptRecord(
                    ProcessorResponseRecord(
                        approvalStatus = processor.approvalStatus,
                        approvalCode = processor.approvalCode,
                        responseCode = processor.responseCode,
                        responseMessage = processor.responseMessage,
                        referenceNumber = processor.referenceNumber,
                        schemeTransactionId = processor.schemeTransactionId,
                    ),
                )
            },
        cardNetwork = source?.card?.scheme,
    )

/**
 * Runs [block], converting every failure the vendor raises into a [CardReaderFailure].
 *
 * A cancellation passes through: a caller withdrawing is not a device problem. `Exception`, not
 * `Throwable`, so an `OutOfMemoryError` is not reported as a reader failure.
 */
private suspend fun <T> mappingFailures(block: suspend () -> T): T =
    try {
        block()
    } catch (uninitialized: FSSDKInternalSDKUninitializedException) {
        throw uninitialized.asFailure(ReaderFailureKind.SESSION_UNUSABLE)
    } catch (disabled: FSSDKNFCDisabledSDKException) {
        throw disabled.asFailure(ReaderFailureKind.CONTACTLESS_UNAVAILABLE)
    } catch (disabled: FSSDKNFCDisabledInternalException) {
        throw disabled.asFailure(ReaderFailureKind.CONTACTLESS_UNAVAILABLE)
    } catch (unsupported: FSSDKNFCNotSupportSDKException) {
        throw unsupported.asFailure(ReaderFailureKind.CONTACTLESS_UNAVAILABLE)
    } catch (unsupported: FSSDKNFCNotSupportedInternalException) {
        throw unsupported.asFailure(ReaderFailureKind.CONTACTLESS_UNAVAILABLE)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (vendor: FiservTTPCardReaderException) {
        throw vendor.asFailure(refusalKind(vendor.code))
    } catch (unexpected: Exception) {
        throw CardReaderFailure(ReaderFailureKind.UNCLASSIFIED, cause = unexpected)
    }

/**
 * What a vendor refusal code means for the session. One exception type covers every arming refusal, so the
 * code is all that separates a denied device from a service that was briefly away.
 *
 * Anything unlisted stays unclassified: a terminal code filed as retryable costs a wasted retry, the
 * reverse hides an outage.
 *
 * Internal so a test can name a code, which is the half of this that rots.
 */
internal fun refusalKind(code: String?): ReaderFailureKind =
    when (code) {
        in DEVICE_DENIED_CODES -> ReaderFailureKind.DEVICE_DENIED
        in UNCONFIRMED_DENIAL_CODES -> ReaderFailureKind.DEVICE_DENIED_UNCONFIRMED
        else -> ReaderFailureKind.UNCLASSIFIED
    }

/** Refusals the vendor documents, or whose meaning its own error table states. */
private val DEVICE_DENIED_CODES =
    setOf(
        // Device denied. The vendor's own text: suspended or deactivated.
        "677",
        // Suspected fraud, reported when the platform attestation behind the reader fails.
        "018",
        // Security violation.
        "670",
        // Invalid device ID or setup.
        "202",
        // This device or OS build is not one the vendor supports.
        "745",
    )

/**
 * Refusals treated as terminal on measurement alone.
 *
 * Move a code up to [DEVICE_DENIED_CODES] once the vendor states what it means, or out of both if it turns
 * out to be transient.
 */
private val UNCONFIRMED_DENIAL_CODES =
    setOf(
        // Undocumented, and absent from the vendor's own error table. Seen only at arming, alternating with
        // 677 on a handset the vendor denies. Raised with the vendor 2026-08-26; unanswered.
        "705",
    )

private fun FiservTTPCardReaderException.asFailure(kind: ReaderFailureKind): CardReaderFailure =
    CardReaderFailure(
        kind = kind,
        code = code,
        type = type,
        field = field,
        detail = message,
        additionalInfo = additionalInfo,
        cause = this,
    )
