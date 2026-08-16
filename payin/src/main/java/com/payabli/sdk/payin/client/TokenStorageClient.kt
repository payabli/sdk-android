package com.payabli.sdk.payin.client

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
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInFailure
import com.payabli.sdk.payin.model.PayInInstrument
import com.payabli.sdk.payin.model.PayInStoreOptions
import com.payabli.sdk.payin.model.PayInStoredMethod
import kotlinx.serialization.SerializationException

/**
 * Stores a card or a bank account, so a later transaction can charge it without the details again.
 *
 * Takes the session's authenticated transport, as [MoneyInClient] does, so the whole SDK has one bearer and
 * one 401 recovery.
 *
 * **This route reports a refusal inside a 200, as `isSuccess: false`**, in the older envelope rather than the
 * one the transaction routes use. A caller that skipped that check would read a refusal as a success.
 *
 * No `idempotencyKey`: a repeat is not recognizable on this route, so a key sent here is read by nobody.
 */
internal class TokenStorageClient(
    private val transport: PayabliTransport,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.NETWORK),
) {
    /**
     * Stores [instrument] against [entryPoint].
     *
     * The bytes carrying the card or account number are overwritten once the request has been written, in a
     * `finally` **after** the call: credential recovery may replay it inside the transport and needs them.
     */
    suspend fun storeMethod(
        entryPoint: String,
        instrument: PayInInstrument,
        options: PayInStoreOptions = PayInStoreOptions(),
        entered: PayInEnteredDetails = PayInEnteredDetails.NONE,
    ): PayInStoredMethod {
        PayInValidation.entryPoint(entryPoint)
        PayInValidation.instrument(instrument, options.validation)

        val outer =
            PayabliJson.format.encodeToString(
                StoreMethodBody.serializer(),
                StoreMethodBody(
                    entryPoint = entryPoint.trim(),
                    customerData = options.customerData.toBody(entered),
                    vendorData = options.vendorData?.toBody(),
                    methodDescription = entered.methodDescription ?: options.methodDescription?.trimOrNull(),
                    fallbackAuth = options.fallbackAuth,
                    fallbackAuthAmount = options.fallbackAuthAmount,
                    source = options.source?.trimOrNull(),
                    subdomain = options.subdomain?.trimOrNull(),
                ),
            )
        val body = PayInBodyWriter.withPaymentMethod(outer, PayInBodyWriter.instrumentFragment(instrument))
        val response =
            try {
                transport.execute(
                    PayabliRequest(
                        method = HttpMethod.POST,
                        path = PayInRoutes.STORE_METHOD,
                        route = PayInRoutes.STORE_METHOD,
                        query = options.query(),
                        body = body,
                    ),
                )
            } finally {
                body.fill(0)
            }

        PayabliHttpErrors.from(response)?.let { failure ->
            logger.warn(
                LogField.safe("event", "payin_store_failed"),
                LogField.safe("route", PayInRoutes.STORE_METHOD),
                LogField.safe("statusCode", response.statusCode),
                LogField.safe("errorCode", failure.code),
            ) { "the stored-method call failed at the transport" }
            throw failure
        }

        val envelope =
            try {
                PayabliJson.format.decodeFromString(StoredMethodEnvelope.serializer(), response.bodyAsText())
            } catch (failure: SerializationException) {
                // SerializationException extends IllegalArgumentException, which also covers a programming
                // error raised inside a serializer.
                throw undecodable(response.statusCode, failure)
            }

        // Success has to be claimed rather than merely not denied. A body of `{}` behind a 200 leaves
        // isSuccess null, and reading that as anything but a failure would report a stored method that was
        // never stored, with no identifier to show for it.
        val stored = envelope.responseData
        // An explicit refusal wins. The result code is the fallback for an envelope that claims neither, and
        // letting it override `isSuccess: false` would report a method as stored against the service's own
        // word for it.
        val approved =
            when (envelope.isSuccess) {
                true -> true
                false -> false
                null -> stored?.resultCode == RESULT_CODE_APPROVED
            }
        if (!approved) {
            logger.warn(
                LogField.safe("event", "payin_store_refused"),
                LogField.safe("route", PayInRoutes.STORE_METHOD),
                // As a string, so a refusal that carried no code records as null rather than as a stand-in
                // number a reader would take for a real one.
                LogField.safe("errorCode", stored?.resultCode?.toString()),
            ) { "the service refused to store the method" }
            throw PayInException.Refused(
                PayInFailure(
                    code = stored?.resultCode?.toString(),
                    reason = stored?.resultText ?: envelope.responseText,
                    explanation = null,
                    action = null,
                    httpStatus = response.statusCode,
                ),
            )
        }
        // A stored method exists to be charged later, so a success with no identifier is a response this
        // client cannot use. Reporting it as stored hands a caller a token it does not have.
        val storedMethodId = stored?.referenceId?.trimOrNull() ?: throw undecodable(response.statusCode, null)
        logger.debug(
            LogField.safe("event", "payin_store_succeeded"),
            LogField.safe("route", PayInRoutes.STORE_METHOD),
            LogField.safe("statusCode", response.statusCode),
        ) { "the method was stored" }
        return PayInStoredMethod(
            storedMethodId = storedMethodId,
            methodReferenceId = stored?.methodReferenceId,
            customerId = stored?.customerId,
            resultCode = stored?.resultCode,
            resultText = stored?.resultText,
        )
    }

    private fun undecodable(
        statusCode: Int,
        cause: Throwable?,
    ): PayInException {
        logger.warn(
            LogField.safe("event", "payin_store_undecodable"),
            LogField.safe("route", PayInRoutes.STORE_METHOD),
            LogField.safe("statusCode", statusCode),
        ) { "the stored-method response could not be read" }
        return PayInException.Undecodable(cause)
    }

    private companion object {
        /** The older envelope's own approval number, which is not an HTTP status. */
        const val RESULT_CODE_APPROVED = 1
    }
}

/** Only the flags that were set. An absent flag lets the paypoint's own default decide. */
private fun PayInStoreOptions.query(): List<Pair<String, String>> =
    buildList {
        achValidation?.let { add(PayInRoutes.QUERY_ACH_VALIDATION to it.wire()) }
        createAnonymous?.let { add(PayInRoutes.QUERY_CREATE_ANONYMOUS to it.wire()) }
        forceCustomerCreation?.let { add(PayInRoutes.QUERY_FORCE_CUSTOMER_CREATION to it.wire()) }
        temporary?.let { add(PayInRoutes.QUERY_TEMPORARY to it.wire()) }
    }
