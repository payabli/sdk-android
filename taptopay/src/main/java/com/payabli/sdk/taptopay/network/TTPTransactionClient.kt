package com.payabli.sdk.taptopay.network

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliHttpErrors
import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import com.payabli.sdk.core.network.Retry
import com.payabli.sdk.core.network.RetryPolicy
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.provider.CardReadResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection.HTTP_NOT_FOUND

/**
 * The two MoneyIn calls a card-present charge is bracketed by.
 *
 * A tap is opened at Payabli before the card is read and closed after it. Between those two the reader
 * charges its own processor directly, so nothing in the middle passes through here.
 *
 * Takes a transport, so the bearer, the one 401 recovery and the replay rule all belong to the session's
 * authenticated transport. No access-token callback: one token path, and one place for a credential to be
 * wrong.
 *
 * **Stateless.** It holds no entry point, caches nothing and sequences nothing. Whoever owns the charge owns
 * the order of the calls.
 *
 * **The two routes are retried differently, and the rule is per route.** Opening is not repeatable: a
 * second attempt is a second transaction, and this route carries no idempotency key to make it one. So
 * [initiate] is never retried, and a caller that loses the answer reconciles rather than asks again.
 * Closing is repeatable, so [update] is.
 *
 * **Neither call sees a card.** The reader charges its processor itself and answers with that processor's
 * response, which [update] forwards to Payabli unread. No Payabli code holds a key that could open it.
 */
internal class TTPTransactionClient(
    private val transport: PayabliTransport,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) {
    /**
     * Opens a transaction and returns the identifier the rest of the charge is keyed by.
     *
     * Nothing is charged here and no card has been read yet, so a failure at this point is the safe one:
     * the charge has not started and there is nothing to reconcile.
     *
     * [deviceId] has to be the identifier registration returned for this handset. It is what ties the
     * charge to this reader, and the identifier that comes back is what the rest of the charge is keyed by,
     * so the two travel together or neither means anything.
     */
    suspend fun initiate(
        entryPoint: String,
        deviceId: String,
        paymentDetails: TapToPayPaymentDetails,
        customer: TapToPayCustomerData = TapToPayCustomerData(),
        invoice: TapToPayInvoiceData = TapToPayInvoiceData(),
        orderDescription: String? = null,
    ): String {
        require(entryPoint.isNotBlank()) { "entryPoint is required" }
        require(deviceId.isNotBlank()) { "deviceId is required: the device has to be registered before it charges" }

        val body =
            InitiateBody(
                entryPoint = entryPoint.trim(),
                // Always written, empty where the caller named nothing, which is the shipping wire format.
                orderDescription = orderDescription.trimOrNull().orEmpty(),
                paymentDetails = paymentDetails.toBody(),
                paymentMethod = InitiatePaymentMethodBody(method = PAYMENT_METHOD_DEVICE, device = deviceId.trim()),
                customerData = customer.toBody(),
                invoiceData = invoice.toBody(),
            )
        val request =
            PayabliRequest.json(
                method = HttpMethod.POST,
                path = TTPRoutes.INITIATE,
                body = body,
                bodySerializer = InitiateBody.serializer(),
                route = TTPRoutes.INITIATE,
            )
        val envelope = read(TTPRoutes.INITIATE, transport.execute(request))
        return envelope.payload?.paymentTransId ?: throw undecodable(TTPRoutes.INITIATE, null)
    }

    /**
     * Closes the transaction with what the reader's processor answered.
     *
     * The outcome the payer saw was decided at the reader, so this call closes the transaction rather than
     * deciding it. [paymentTransId] is the only part it has to get right; the body travels for the record.
     */
    suspend fun update(
        paymentTransId: String,
        result: CardReadResult,
    ) = send(
        paymentTransId,
        PayabliJson.format.encodeToString(JsonObject.serializer(), updateSuccessBody(result)),
    )

    /**
     * Closes the transaction after a tap that never completed.
     *
     * Sent so an opened transaction is not left standing. [description] is a record rather than a result:
     * a card that was never read has no outcome anywhere, and nothing about the close depends on the text.
     */
    suspend fun updateAfterFailedRead(
        paymentTransId: String,
        description: String,
    ) = send(
        paymentTransId,
        PayabliJson.format.encodeToString(
            UpdateFailureBody.serializer(),
            UpdateFailureBody(
                UpdateErrorDetail(
                    title = NFC_FAILURE_TITLE,
                    description = description,
                    failureReason = NFC_FAILURE_REASON,
                ),
            ),
        ),
    )

    /**
     * The shared half of the two closes: a `PATCH` carrying [body], retried.
     *
     * **Any 2xx is success and the response is not decoded.** The outcome the payer saw was decided at the
     * reader, so nothing that comes back here changes it. Decoding it would invite a caller to treat a lag
     * between the two as a different payment.
     */
    private suspend fun send(
        paymentTransId: String,
        body: String,
    ) {
        require(paymentTransId.isNotBlank()) { "paymentTransId is required" }
        val encoded = body.toByteArray(Charsets.UTF_8)
        Retry.run(route = TTPRoutes.UPDATE, policy = retryPolicy, logger = logger) {
            val response =
                transport.execute(
                    PayabliRequest(
                        method = HttpMethod.PATCH,
                        path = TTPRoutes.update(paymentTransId),
                        route = TTPRoutes.UPDATE,
                        headers = mapOf(CONTENT_TYPE to APPLICATION_JSON),
                        body = encoded,
                    ),
                )
            failureFor(TTPRoutes.UPDATE, response)?.let { throw it }
            logger.debug(
                LogField.safe("event", "ttp_transaction_closed"),
                LogField.safe("route", TTPRoutes.UPDATE),
                LogField.safe("statusCode", response.statusCode),
                LogField.safe("contentLength", encoded.size),
            ) { "the transaction was closed" }
        }
    }

    /**
     * Reads an opening response, in the order the classification depends on.
     *
     * 1. The status first, because a transport failure means what follows is not this service speaking.
     * 2. Then the envelope, because a refusal arrives as a `D`-prefixed code behind a 2xx and skipping this
     *    is how a decline reads as an approval. A success is a 201 as often as a 200, so neither is
     *    asserted.
     * 3. Only then the payload.
     */
    private fun read(
        route: String,
        response: PayabliResponse,
    ): PayabliV2Envelope<InitiatePayload> {
        failureFor(route, response)?.let { throw it }
        val envelope =
            try {
                PayabliJson.format.decodeFromString(
                    PayabliV2Envelope.serializer(InitiatePayload.serializer()),
                    response.bodyAsText(),
                )
            } catch (failure: SerializationException) {
                // The supertype is not caught: SerializationException extends IllegalArgumentException, so
                // catching that would swallow a programming error raised from inside a serializer.
                throw undecodable(route, failure)
            }
        if (!envelope.isApproved) {
            logger.warn(
                LogField.safe("event", "ttp_transaction_not_approved"),
                LogField.safe("route", route),
                LogField.safe("statusCode", response.statusCode),
                LogField.safe("errorCode", envelope.code),
            ) { "the service did not approve the transaction" }
            // Only a `D` is the payment being refused. Anything else is the service reporting a problem, and
            // a caller acts on the two differently.
            throw if (envelope.isDeclined) {
                TTPTransactionException.Refused(envelope.code, envelope.reason)
            } else {
                TTPTransactionException.ServiceRejected(envelope.code, envelope.reason)
            }
        }
        logger.debug(
            LogField.safe("event", "ttp_transaction_opened"),
            LogField.safe("route", route),
            LogField.safe("statusCode", response.statusCode),
        ) { "the transaction was opened" }
        return envelope
    }

    /**
     * The transport-level reading of a status, or null when there is nothing wrong with it.
     *
     * A 404 is classified before the shared table, because it means something on these two routes that it
     * does not mean anywhere else and the shared table has no way to know that.
     */
    private fun failureFor(
        route: String,
        response: PayabliResponse,
    ): Throwable? {
        val failure =
            if (response.statusCode == HTTP_NOT_FOUND) {
                TTPTransactionException.NotEnabled()
            } else {
                PayabliHttpErrors.from(response)
            } ?: return null
        logger.warn(
            LogField.safe("event", "ttp_transaction_call_failed"),
            LogField.safe("route", route),
            LogField.safe("statusCode", response.statusCode),
        ) { "the transaction call failed at the transport" }
        return failure
    }

    private fun undecodable(
        route: String,
        cause: Throwable?,
    ): TTPTransactionException {
        logger.warn(
            LogField.safe("event", "ttp_transaction_undecodable"),
            LogField.safe("route", route),
        ) { "the transaction response could not be read" }
        return TTPTransactionException.Undecodable(cause)
    }

    private companion object {
        const val CONTENT_TYPE = "Content-Type"
        const val APPLICATION_JSON = "application/json"
    }
}
