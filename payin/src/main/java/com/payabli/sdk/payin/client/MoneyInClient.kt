package com.payabli.sdk.payin.client

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliHttpErrors
import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInFailure
import com.payabli.sdk.payin.model.PayInRequest
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.model.PayInTransaction
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.payin.model.leavesThePaymentOpen
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection

/**
 * The MoneyIn transaction calls: capture, authorize, capture an authorization, and void.
 *
 * Takes a transport, so the bearer, the one 401 recovery and the replay rule all belong to the session's
 * authenticated transport. No access-token callback: one token path, and one place for a credential to be
 * wrong.
 *
 * **Stateless.** It holds no entry point, caches nothing and sequences nothing. Whoever owns the flow owns
 * the order of calls.
 *
 * **Nothing here is wrapped in `Retry`, and that is per route.** A capture is not repeatable: sending it twice
 * charges twice unless the repeat can be recognized as one, which is what
 * [PayInTransactionOptions.idempotencyKey] is for. Every route here is reached through the flow, which
 * reserves a key per attempt, so one is always sent.
 *
 * And the transport can send one of these a second time on its own. Credential recovery replays a request
 * whose rejection was an exact 401, which on these routes cannot take a second payment, so it costs a wasted
 * refresh and nothing else. "Not wrapped in `Retry`" is not a promise that a request reaches the service once.
 *
 * **An input problem can reach a caller as an expired token.** The status on a v2 error is not always the one
 * the input deserves, and the call site has no say in it, so a bad request can arrive as a 401. The
 * authenticated transport treats any 401 as a credential rejection: it refreshes, replays, and on the second
 * 401 throws rather than returning the response, so this class never sees the body and cannot reclassify it.
 * Telling the two apart has to happen in the layer that decides what a credential rejection is.
 */
internal class MoneyInClient(
    private val transport: PayabliTransport,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.NETWORK),
) {
    /**
     * Takes a payment.
     *
     * @param entryPoint the partner integration point the payment is attributed to.
     * @param request the payment to take.
     * @param entered what the payer typed, for the customer data.
     * @param idempotencyKey the attempt's key, or null to send none.
     * @return the outcome the service reported.
     */
    suspend fun capture(
        entryPoint: String,
        request: PayInRequest,
        entered: PayInEnteredDetails = PayInEnteredDetails.NONE,
        idempotencyKey: String? = request.options.idempotencyKey,
    ): PayInResult {
        validate(entryPoint, request)
        return send(
            route = PayInRoutes.CAPTURE,
            path = PayInRoutes.CAPTURE,
            request = request,
            entryPoint = entryPoint,
            entered = entered,
            allowsAchValidation = true,
            idempotencyKey = idempotencyKey,
        )
    }

    /**
     * Authorizes a payment without taking it.
     *
     * Refused here for a method the route does not take, so a caller learns it without a round trip.
     *
     * @param entryPoint the partner integration point the payment is attributed to.
     * @param request the payment to authorize.
     * @param entered what the payer typed, for the customer data.
     * @param idempotencyKey the attempt's key, or null to send none.
     * @return the outcome the service reported.
     */
    suspend fun authorize(
        entryPoint: String,
        request: PayInRequest,
        entered: PayInEnteredDetails = PayInEnteredDetails.NONE,
        idempotencyKey: String? = request.options.idempotencyKey,
    ): PayInResult {
        if (!request.paymentMethod.isAuthorizable) {
            throw PayInException.InvalidInput("paymentMethod", "This payment method cannot be authorized")
        }
        validate(entryPoint, request)
        return send(
            route = PayInRoutes.AUTHORIZE,
            path = PayInRoutes.AUTHORIZE,
            request = request,
            entryPoint = entryPoint,
            entered = entered,
            // This route has no achValidation, so sending it would be noise.
            allowsAchValidation = false,
            idempotencyKey = idempotencyKey,
        )
    }

    /**
     * Captures a transaction that was authorized earlier, in full or in part.
     *
     * One of the two whose path differs from its route, [void] being the other: the identifier is in the
     * path, so the template is what a log may carry.
     *
     * @param request the authorization to capture, in full or in part.
     * @param idempotencyKey the attempt's key, or null to send none.
     * @return the outcome the service reported.
     */
    suspend fun captureAuthorized(
        request: PayInAuthorizedRequest,
        idempotencyKey: String? = request.idempotencyKey,
    ): PayInResult {
        PayInValidation.transId(request.transId)
        PayInValidation.paymentDetails(request.paymentDetails)

        // No buffered field in this body, so it goes through the ordinary JSON request builder: the method was
        // settled when the transaction was authorized, and only the amount is being sent now.
        val payabliRequest =
            PayabliRequest.json(
                method = HttpMethod.POST,
                path = PayInRoutes.captureAuthorized(request.transId.trim()),
                body = AuthorizedCaptureBody(request.paymentDetails.toBody()),
                bodySerializer = AuthorizedCaptureBody.serializer(),
                route = PayInRoutes.CAPTURE_AUTHORIZED,
                headers = payInHeaders { idempotencyKey(idempotencyKey) },
            )
        return read(PayInRoutes.CAPTURE_AUTHORIZED, transport.execute(payabliRequest))
    }

    /**
     * Reverses a transaction the service will still reverse.
     *
     * No body: the route carries the identifier in its path and takes nothing else, so there is no partial
     * void. Which states can be reversed is the service's to decide and is not mirrored here; a state it
     * refuses arrives as the refusal it sent.
     *
     * @param transId the transaction to reverse.
     * @param idempotencyKey the attempt's key, or null to send none.
     * @return the outcome the service reported.
     */
    suspend fun void(
        transId: String,
        idempotencyKey: String? = null,
    ): PayInResult {
        PayInValidation.transId(transId)

        val payabliRequest =
            PayabliRequest(
                method = HttpMethod.POST,
                path = PayInRoutes.void(transId.trim()),
                route = PayInRoutes.VOID,
                headers = payInHeaders { idempotencyKey(idempotencyKey) },
            )
        return read(PayInRoutes.VOID, transport.execute(payabliRequest))
    }

    /**
     * Reads a transaction back by its identifier.
     *
     * A caller refused this transaction gets [PayabliErrorType.PERMISSION_DENIED], with no token refresh. An
     * identifier with nothing visible under it yet gets [PayabliErrorType.TRANSACTION_NOT_FOUND]. A record that
     * names no transaction, or another one, is [PayInException.Undecodable].
     *
     * @param transId the transaction to read.
     * @return the transaction, filled with the fields a read carries.
     */
    suspend fun readTransaction(transId: String): PayInTransaction {
        PayInValidation.transId(transId)
        val sent = transId.trim()

        val response =
            transport.execute(
                PayabliRequest(
                    method = HttpMethod.GET,
                    path = PayInRoutes.read(sent),
                    route = PayInRoutes.READ,
                    isCallerRefusal = ::isNotPermitted,
                ),
            )
        readRefusal(response)?.let { failure ->
            logger.warn(
                LogField.safe("event", "payin_read_refused"),
                LogField.safe("route", PayInRoutes.READ),
                LogField.safe("statusCode", response.statusCode),
                LogField.safe("errorCode", failure.type),
            ) { "the transaction read was refused" }
            throw failure
        }
        PayabliHttpErrors.from(response)?.let { failure ->
            logger.warn(
                LogField.safe("event", "payin_call_failed"),
                LogField.safe("route", PayInRoutes.READ),
                LogField.safe("statusCode", response.statusCode),
                LogField.safe("errorCode", failure.type),
            ) { "the transaction call failed at the transport" }
            throw failure
        }
        val record =
            try {
                PayabliJson.format.decodeFromString(TransactionPayload.serializer(), response.bodyAsText())
            } catch (failure: SerializationException) {
                throw undecodable(PayInRoutes.READ, response.statusCode, failure)
            }
        // Otherwise the caller would get the status of a different payment.
        if (record.paymentTransId != sent) throw undecodable(PayInRoutes.READ, response.statusCode, null)

        logger.debug(
            LogField.safe("event", "payin_call_succeeded"),
            LogField.safe("route", PayInRoutes.READ),
            LogField.safe("statusCode", response.statusCode),
        ) { "the transaction call succeeded" }
        return record.toTransaction()
    }

    private fun validate(
        entryPoint: String,
        request: PayInRequest,
    ) {
        PayInValidation.entryPoint(entryPoint)
        PayInValidation.paymentDetails(request.paymentDetails)
        PayInValidation.paymentMethod(request.paymentMethod, request.options.validation)
    }

    /**
     * Builds the body, sends it, and overwrites the bytes afterwards. The wipe is in a `finally`
     * **after** the call, because credential recovery may replay the request inside the transport
     * and needs the bytes intact to do it.
     *
     * @param route the route template a log may carry.
     * @param path the resolved path, carrying the request's identifiers.
     * @param request the payment to send.
     * @param entryPoint the partner integration point the payment is attributed to.
     * @param entered what the payer typed, for the customer data.
     * @param allowsAchValidation whether the route takes the achValidation flag.
     * @param idempotencyKey the attempt's key, or null to send none.
     * @return the outcome the service reported.
     */
    private suspend fun send(
        route: String,
        path: String,
        request: PayInRequest,
        entryPoint: String,
        entered: PayInEnteredDetails,
        allowsAchValidation: Boolean,
        idempotencyKey: String?,
    ): PayInResult {
        val outer =
            PayabliJson.format.encodeToString(
                MoneyInBody.serializer(),
                request.options.toBody(entryPoint, entered),
            )
        val body = PayInBodyWriter.withPaymentMethod(outer, PayInBodyWriter.methodFragment(request.paymentMethod))
        val response =
            try {
                transport.execute(
                    PayabliRequest(
                        method = HttpMethod.POST,
                        path = path,
                        route = route,
                        query = request.options.query(allowsAchValidation),
                        headers = request.options.headers(idempotencyKey),
                        body = body,
                    ),
                )
            } finally {
                body.fill(0)
            }
        return read(route, response)
    }

    /**
     * Reads the outcome: a code that leaves it open, whatever the status; then the status; then the
     * envelope, because a decline behind a 2xx otherwise reads as an approval; then the payload. A success
     * is a 201 as often as a 200.
     *
     * @param route the route template a log may carry.
     * @param response the answered request.
     * @return the outcome the service reported.
     */
    private fun read(
        route: String,
        response: PayabliResponse,
    ): PayInResult {
        val text = response.bodyAsText()
        val said = AnswerFields.of(text)
        OPEN_OUTCOME_CODES[said.code]?.let { type ->
            logger.warn(
                LogField.safe("event", "payin_transaction_not_approved"),
                LogField.safe("route", route),
                LogField.safe("statusCode", response.statusCode),
                LogField.safe("errorCode", said.code),
            ) { "the service left the transaction's outcome open" }
            throw PayInException.ServiceError(said.failure(response.statusCode), type)
        }
        PayabliHttpErrors.from(response)?.let { failure ->
            logger.warn(
                LogField.safe("event", "payin_call_failed"),
                LogField.safe("route", route),
                LogField.safe("statusCode", response.statusCode),
                LogField.safe("errorCode", failure.type),
            ) { "the transaction call failed at the transport" }
            // An open outcome keeps the transaction its body names, which the status mapping cannot carry.
            throw if (failure.type.leavesThePaymentOpen && said.paymentTransId != null) {
                PayInException.ServiceError(said.failure(response.statusCode), failure.type)
            } else {
                failure
            }
        }
        val (envelope, parties) =
            try {
                val body = PayabliJson.format.parseToJsonElement(text)
                PayabliJson.format.decodeFromJsonElement(
                    PayabliV2Envelope.serializer(TransactionPayload.serializer()),
                    body,
                ) to
                    PayabliJson.format
                        .decodeFromJsonElement(
                            PayabliV2Envelope.serializer(ApprovalPartiesPayload.serializer()),
                            body,
                        ).payload
            } catch (failure: SerializationException) {
                // The supertype is not caught: SerializationException extends IllegalArgumentException, so
                // catching that would swallow a programming error raised from inside a serializer.
                throw undecodable(route, response.statusCode, failure, said.paymentTransId)
            }
        if (!envelope.isApproved) {
            val failure =
                PayInFailure(
                    code = envelope.code,
                    reason = envelope.reason,
                    explanation = envelope.explanation,
                    action = envelope.action,
                    httpStatus = response.statusCode,
                    paymentTransId = said.paymentTransId,
                )
            logger.warn(
                LogField.safe("event", "payin_transaction_not_approved"),
                LogField.safe("route", route),
                LogField.safe("errorCode", envelope.code),
            ) { "the service did not approve the transaction" }
            // Only a `D` is a decline. An `E`, or a family this SDK does not know, is the service reporting a
            // problem rather than the payment being refused, and a caller acts on the two differently.
            throw if (envelope.isDeclined) {
                PayInException.Refused(failure)
            } else {
                PayInException.ServiceError(failure)
            }
        }
        logger.debug(
            LogField.safe("event", "payin_call_succeeded"),
            LogField.safe("route", route),
            LogField.safe("statusCode", response.statusCode),
        ) { "the transaction call succeeded" }
        return PayInResult(
            code = envelope.code,
            reason = envelope.reason,
            explanation = envelope.explanation,
            action = envelope.action,
            transaction = envelope.payload?.toTransaction(parties),
        )
    }

    private fun undecodable(
        route: String,
        statusCode: Int,
        cause: Throwable?,
        paymentTransId: String? = null,
    ): PayInException {
        logger.warn(
            LogField.safe("event", "payin_response_undecodable"),
            LogField.safe("route", route),
            LogField.safe("statusCode", statusCode),
        ) { "the transaction response could not be read" }
        return PayInException.Undecodable(cause, paymentTransId)
    }
}

/**
 * The codes that leave a payment's outcome open whatever status carries them, and the type each lands as.
 * The status carrying them differs between environments, so it cannot decide these.
 */
private val OPEN_OUTCOME_CODES: Map<String?, PayabliErrorType> =
    mapOf(
        "E9060" to PayabliErrorType.UNKNOWN,
        "E9999" to PayabliErrorType.SERVER_ERROR,
    )

/**
 * The v2 envelope's fields, each read on its own, so a missing or malformed field costs only itself.
 *
 * @property paymentTransId the transaction the body names, trimmed, or null when absent or blank once trimmed.
 */
private class AnswerFields(
    val code: String?,
    val reason: String?,
    val explanation: String?,
    val action: String?,
    val paymentTransId: String?,
) {
    /**
     * The failure these fields describe.
     *
     * @param httpStatus the status the answer arrived with.
     * @return the failure, naming the transaction where the answer named one.
     */
    fun failure(httpStatus: Int): PayInFailure =
        PayInFailure(code, reason, explanation, action, httpStatus, paymentTransId)

    companion object {
        /**
         * Reads [text]'s fields, each on its own.
         *
         * @param text the response body.
         * @return the fields, each null where the body does not carry it as a string.
         */
        fun of(text: String): AnswerFields {
            val root =
                try {
                    PayabliJson.format.parseToJsonElement(text) as? JsonObject
                } catch (_: SerializationException) {
                    null
                }
            return AnswerFields(
                code = root.string("code"),
                reason = root.string("reason"),
                explanation = root.string("explanation"),
                action = root.string("action"),
                // The duplicate-payment 409 is the older shape, naming the earlier payment.
                paymentTransId =
                    (root?.get("data") as? JsonObject).identifier("paymentTransId")
                        ?: (root?.get("responseData") as? JsonObject).identifier("referenceId"),
            )
        }

        /**
         * A string member, or null.
         *
         * @param key the member's name.
         * @return the member's value when it is a JSON string, or null.
         */
        private fun JsonObject?.string(key: String): String? =
            (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        /**
         * A transaction identifier, under exactly [key].
         *
         * @param key the member's name.
         * @return the identifier trimmed, or null when absent or blank once trimmed.
         */
        private fun JsonObject?.identifier(key: String): String? = string(key)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * The wire body for a transaction, without `paymentMethod`, which the writer adds.
 *
 * @param entryPoint the partner integration point, trimmed as the request writers send it.
 * @param entered what the payer typed, for the customer data.
 * @return the wire body for the transaction.
 */
private fun PayInTransactionOptions.toBody(
    entryPoint: String,
    entered: PayInEnteredDetails,
): MoneyInBody =
    MoneyInBody(
        entryPoint = entryPoint.trim(),
        paymentDetails = paymentDetails.toBody(),
        customerData = customerData.toBody(entered),
        accountId = accountId?.trimOrNull(),
        ipaddress = ipAddress?.trimOrNull(),
        orderId = orderId?.trimOrNull(),
        orderDescription = orderDescription?.trimOrNull(),
        source = source?.trimOrNull(),
        subdomain = subdomain?.trimOrNull(),
        subscriptionId = subscriptionId,
    )

/**
 * The transaction this record describes.
 *
 * @param parties the connector and payer an approval names, or null for a read, which reports neither.
 * @return the transaction.
 */
private fun TransactionPayload.toTransaction(parties: ApprovalPartiesPayload? = null): PayInTransaction =
    PayInTransaction(
        paymentTransId = paymentTransId,
        gatewayTransId = gatewayTransId,
        orderId = orderId,
        method = method,
        transStatus = transStatus,
        paypointId = paypointId,
        totalAmount = totalAmount,
        netAmount = netAmount,
        feeAmount = feeAmount,
        surchargeFee = surchargeFee,
        connectorName = parties?.connectorName,
        customerId = parties?.payorId,
    )

/**
 * The older envelope's numeric code, or null when the body carries none in that shape.
 *
 * @param response the answered request.
 * @return the refusal's code, or null.
 */
private fun legacyCode(response: PayabliResponse): Int? =
    try {
        PayabliJson.format.decodeFromString(LegacyRefusal.serializer(), response.bodyAsText()).responseCode
    } catch (_: SerializationException) {
        null
    }

/**
 * Whether a read was refused because this caller may not read the transaction.
 *
 * @param response the answered request.
 * @return true for a 401 carrying [LegacyRefusal.NOT_PERMITTED].
 */
private fun isNotPermitted(response: PayabliResponse): Boolean =
    response.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED && legacyCode(response) == LegacyRefusal.NOT_PERMITTED

/**
 * Maps the two refusals specific to a read. Any other failure goes to the shared status mapping.
 *
 * @param response the answered request.
 * @return the refusal, or null when the response is neither.
 */
private fun readRefusal(response: PayabliResponse): PayabliGenericException? =
    when {
        isNotPermitted(response) ->
            PayabliGenericException(PayabliErrorType.PERMISSION_DENIED, PayabliErrorType.PERMISSION_DENIED.message)
        response.statusCode == HttpURLConnection.HTTP_BAD_REQUEST &&
            legacyCode(response) == LegacyRefusal.NOT_VISIBLE ->
            PayabliGenericException(
                PayabliErrorType.TRANSACTION_NOT_FOUND,
                PayabliErrorType.TRANSACTION_NOT_FOUND.message,
            )
        else -> null
    }

/**
 * Only the flags that were set: an absent flag and a false one are different statements to the service.
 *
 * @param allowsAchValidation whether the route takes the achValidation flag.
 * @return the query pairs the route takes.
 */
private fun PayInTransactionOptions.query(allowsAchValidation: Boolean): List<Pair<String, String>> =
    buildList {
        if (allowsAchValidation) achValidation?.let { add(PayInRoutes.QUERY_ACH_VALIDATION to it.wire()) }
        forceCustomerCreation?.let { add(PayInRoutes.QUERY_FORCE_CUSTOMER_CREATION to it.wire()) }
        sameDayAch?.let { add(PayInRoutes.QUERY_SAME_DAY_ACH to it.wire()) }
        isAsync?.let { add(PayInRoutes.QUERY_IS_ASYNC to it.wire()) }
        useCaching?.let { add(PayInRoutes.QUERY_USE_CACHING to it.wire()) }
    }

/**
 * [key] rather than this object's own, because the flow mints one when the caller set none. Passed in
 * rather than read here so there is one answer per attempt and one place that decides it: a caller that
 * set a key still sends exactly that key, and the flow only fills a gap.
 *
 * @param key the attempt's key, or null to send none.
 * @return the headers the request carries.
 */
private fun PayInTransactionOptions.headers(key: String?): Map<String, String> =
    payInHeaders {
        idempotencyKey(key)
        validationCode(validationCode)
    }
