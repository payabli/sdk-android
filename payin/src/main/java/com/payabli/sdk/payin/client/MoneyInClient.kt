package com.payabli.sdk.payin.client

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliValidationException
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
import kotlinx.serialization.SerializationException
import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * The three MoneyIn transaction calls: capture, authorise, and capture an authorisation.
 *
 * Takes a transport rather than fetching a credential, so the bearer, the one 401 recovery and the replay
 * rule all belong to the session's authenticated transport. There is no access-token callback here and there
 * should not be: a second token path is a second place for a credential to be wrong.
 *
 * **Stateless.** It holds no entry point, caches nothing and sequences nothing. Whoever owns the flow owns
 * the order of calls.
 *
 * **Nothing here is wrapped in `Retry`, and that is per route.** A capture is not repeatable: sending it twice
 * charges twice unless the service can recognise the repeat, which is what [PayInRequest.idempotencyKey] is
 * for. The service runs its idempotency middleware over these paths, so a caller that sets a key can retry
 * safely and a caller that does not cannot.
 *
 * And the transport can send one of these a second time on its own. Credential recovery replays a request
 * whose rejection was an exact 401, which for these routes is refused before the payment is processed, so it
 * costs a wasted refresh rather than a double charge. Do not read "not wrapped in `Retry`" as a promise that a
 * request reaches the service once.
 */
internal class MoneyInClient(
    private val transport: PayabliTransport,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.NETWORK),
) {
    /** Takes a payment. */
    suspend fun capture(
        entryPoint: String,
        request: PayInRequest,
    ): PayInResult {
        validate(entryPoint, request)
        return send(
            route = PayInRoutes.CAPTURE,
            path = PayInRoutes.CAPTURE,
            request = request,
            entryPoint = entryPoint,
            allowsAchValidation = true,
        )
    }

    /**
     * Authorises a payment without taking it.
     *
     * Refused here rather than by the service for anything but entered card data: the service authorises a
     * card and nothing else, and a caller learns that without a round trip.
     */
    suspend fun authorize(
        entryPoint: String,
        request: PayInRequest,
    ): PayInResult {
        if (!request.paymentMethod.isAuthorizable) {
            throw PayInException.InvalidInput("paymentMethod", "Only card details can be authorised")
        }
        validate(entryPoint, request)
        return send(
            route = PayInRoutes.AUTHORIZE,
            path = PayInRoutes.AUTHORIZE,
            request = request,
            entryPoint = entryPoint,
            // The service takes no achValidation on this route, so sending it would be noise.
            allowsAchValidation = false,
        )
    }

    /**
     * Captures a transaction that was authorised earlier, in full or in part.
     *
     * The only call in this module whose path differs from its route: the identifier is in the path, so the
     * template is what a log may carry.
     */
    suspend fun captureAuthorized(request: PayInAuthorizedRequest): PayInResult {
        PayInValidation.transId(request.transId)
        PayInValidation.paymentDetails(request.paymentDetails)

        // No buffered field in this body, so it goes through the ordinary JSON request builder: the method was
        // settled when the transaction was authorised, and only the amount is being sent now.
        val payabliRequest =
            PayabliRequest.json(
                method = HttpMethod.POST,
                path = PayInRoutes.captureAuthorized(request.transId.trim()),
                body = AuthorizedCaptureBody(request.paymentDetails.toBody()),
                bodySerializer = AuthorizedCaptureBody.serializer(),
                route = PayInRoutes.CAPTURE_AUTHORIZED,
            )
        return read(PayInRoutes.CAPTURE_AUTHORIZED, transport.execute(payabliRequest))
    }

    private fun validate(
        entryPoint: String,
        request: PayInRequest,
    ) {
        PayInValidation.entryPoint(entryPoint)
        PayInValidation.paymentDetails(request.paymentDetails)
        PayInValidation.paymentMethod(request.paymentMethod, request.validation)
    }

    /**
     * Builds the body, sends it, and overwrites the bytes afterwards.
     *
     * The wipe is in a `finally` **after** the call rather than before it, because credential recovery may
     * replay the request inside the transport and needs the bytes intact to do it.
     */
    private suspend fun send(
        route: String,
        path: String,
        request: PayInRequest,
        entryPoint: String,
        allowsAchValidation: Boolean,
    ): PayInResult {
        val outer =
            PayabliJson.format.encodeToString(
                MoneyInBody.serializer(),
                request.toBody(entryPoint),
            )
        val body = PayInBodyWriter.withPaymentMethod(outer, PayInBodyWriter.methodFragment(request.paymentMethod))
        val response =
            try {
                transport.execute(
                    PayabliRequest(
                        method = HttpMethod.POST,
                        path = path,
                        route = route,
                        query = request.query(allowsAchValidation),
                        headers = request.headers(),
                        body = body,
                    ),
                )
            } finally {
                body.fill(0)
            }
        return read(route, response)
    }

    /**
     * Reads the outcome, in the order the classification depends on.
     *
     * 1. The status first, with an override for 401, below.
     * 2. Then the envelope, because a refusal arrives as a `D`-prefixed code behind a 2xx and skipping this
     *    is how a decline reads as an approval. A success is a 201 as often as a 200, so neither is asserted.
     * 3. Only then the payload.
     */
    private fun read(
        route: String,
        response: PayabliResponse,
    ): PayInResult {
        val transportFailure =
            PayabliHttpErrors.from(response) { status ->
                inputProblemReportedAsUnauthorized(status, response)
            }
        transportFailure?.let { failure ->
            logger.warn(
                LogField.safe("event", "payin_call_failed"),
                LogField.safe("route", route),
                LogField.safe("statusCode", response.statusCode),
                LogField.safe("errorCode", failure.code),
            ) { "the transaction call failed at the transport" }
            throw failure
        }
        val envelope =
            try {
                PayabliJson.format.decodeFromString(
                    PayabliV2Envelope.serializer(TransactionPayload.serializer()),
                    response.bodyAsText(),
                )
            } catch (failure: SerializationException) {
                // The supertype is not caught: SerializationException extends IllegalArgumentException, so
                // catching that would swallow a programming error raised from inside a serializer.
                throw undecodable(route, response.statusCode, failure)
            }
        if (!envelope.isApproved) {
            logger.warn(
                LogField.safe("event", "payin_transaction_refused"),
                LogField.safe("route", route),
                LogField.safe("errorCode", envelope.code),
            ) { "the service refused the transaction" }
            throw PayInException.Refused(
                PayInFailure(
                    code = envelope.code,
                    reason = envelope.reason,
                    explanation = envelope.explanation,
                    action = envelope.action,
                    httpStatus = response.statusCode,
                ),
            )
        }
        logger.debug(
            LogField.safe("event", "payin_call_succeeded"),
            LogField.safe("route", route),
            LogField.safe("statusCode", response.statusCode),
        ) { "the transaction call succeeded" }
        return PayInResult(code = envelope.code, transaction = envelope.payload?.toTransaction())
    }

    /**
     * What a 401 means on these routes, which is not what it means anywhere else.
     *
     * The service decides the status of a v2 error from a lookup table rather than at the call site, and a
     * missing entry point is one of the input problems that comes back as 401. Left alone, `:core` maps that
     * to an expired token, and a host app reading that re-initialises a session that was never the problem.
     *
     * **This changes the classification only.** The refresh and the single replay happen inside the
     * authenticated transport before this code sees the response, and nothing here can prevent them. A 400 is
     * already right, and a 500 from the table's fallback is a server error by any reading, so neither is
     * touched.
     *
     * The body is re-read through [PayabliHttpErrors] under a 400, which is the only way to reuse `:core`'s
     * problem-details decode: the override is handed a status and not a body, and re-implementing that decode
     * here would mean a second copy of the one that accepts both `errors` shapes. The status is then put back
     * to what the service actually sent, so nothing downstream reports a 400 that never happened.
     *
     * **A 401 is only reclassified when the body says something about the input.** `:core` maps any 400 to a
     * validation error, empty body included, so re-reading without this check would relabel a genuine
     * credential rejection — which arrives with no body at all — as an input problem, and a host app would
     * stop refreshing a token that really had expired. A field error or the service's own code is what makes
     * it an input problem; neither present means it is what it says it is.
     */
    private fun inputProblemReportedAsUnauthorized(
        statusCode: Int,
        response: PayabliResponse,
    ): PayabliException? {
        if (statusCode != HTTP_UNAUTHORIZED) return null
        val parsed =
            PayabliHttpErrors.from(PayabliResponse(HTTP_BAD_REQUEST, response.headers, response.body))
                as? PayabliValidationException
                ?: return null
        if (parsed.fieldErrors.isEmpty() && parsed.rawCode == null) return null
        return PayabliValidationException(
            httpStatus = statusCode,
            reason = parsed.reason,
            detail = parsed.detail,
            type = parsed.type,
            instance = parsed.instance,
            rawCode = parsed.rawCode,
            fieldErrors = parsed.fieldErrors,
        )
    }

    private fun undecodable(
        route: String,
        statusCode: Int,
        cause: Throwable?,
    ): PayInException {
        logger.warn(
            LogField.safe("event", "payin_response_undecodable"),
            LogField.safe("route", route),
            LogField.safe("statusCode", statusCode),
        ) { "the transaction response could not be read" }
        return PayInException.Undecodable(cause)
    }
}

/** The wire body for a transaction, without `paymentMethod`, which the writer adds. */
private fun PayInRequest.toBody(entryPoint: String): MoneyInBody =
    MoneyInBody(
        entryPoint = entryPoint.trim(),
        paymentDetails = paymentDetails.toBody(),
        customerData = customerData?.toBody(),
        accountId = accountId?.trimOrNull(),
        ipaddress = ipAddress?.trimOrNull(),
        orderId = orderId?.trimOrNull(),
        orderDescription = orderDescription?.trimOrNull(),
        source = source?.trimOrNull(),
        subdomain = subdomain?.trimOrNull(),
        subscriptionId = subscriptionId,
    )

private fun TransactionPayload.toTransaction(): PayInTransaction =
    PayInTransaction(
        paymentTransId = paymentTransId,
        gatewayTransId = gatewayTransId,
        orderId = orderId,
        method = method,
        transStatus = transStatus,
        totalAmount = totalAmount,
        netAmount = netAmount,
        connectorName = connectorName,
        customerId = payorId,
    )

/** Only the flags that were set: an absent flag and a false one are different statements to the service. */
private fun PayInRequest.query(allowsAchValidation: Boolean): List<Pair<String, String>> =
    buildList {
        if (allowsAchValidation) achValidation?.let { add(PayInRoutes.QUERY_ACH_VALIDATION to it.wire()) }
        forceCustomerCreation?.let { add(PayInRoutes.QUERY_FORCE_CUSTOMER_CREATION to it.wire()) }
        sameDayAch?.let { add(PayInRoutes.QUERY_SAME_DAY_ACH to it.wire()) }
        isAsync?.let { add(PayInRoutes.QUERY_IS_ASYNC to it.wire()) }
        useCaching?.let { add(PayInRoutes.QUERY_USE_CACHING to it.wire()) }
    }

private fun PayInRequest.headers(): Map<String, String> =
    buildMap {
        idempotencyKey?.trimOrNull()?.let { put(PayInRoutes.HEADER_IDEMPOTENCY_KEY, it) }
        validationCode?.trimOrNull()?.let { put(PayInRoutes.HEADER_VALIDATION_CODE, it) }
    }
