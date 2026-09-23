package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import com.payabli.sdk.payin.client.TEST_ACCOUNT
import com.payabli.sdk.payin.client.TEST_EXPIRY
import com.payabli.sdk.payin.client.TEST_EXPIRY_WIRE
import com.payabli.sdk.payin.client.TEST_PAN
import com.payabli.sdk.payin.client.TEST_ROUTING
import com.payabli.sdk.payin.client.TEST_SECURITY_CODE
import com.payabli.sdk.payin.client.testDetails
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.model.PayInCardData
import com.payabli.sdk.payin.model.PayInPaymentMethod
import com.payabli.sdk.payin.model.PayInRequest
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.payin.model.SensitiveDigits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.KSerializer

/** A v2 approval, with the transaction record it carries in `data`. */
internal val APPROVED_TRANSACTION: String =
    """
    {"code":"A0000","reason":"Approved","explanation":"Transaction approved","action":"none",
     "data":{"paymentTransId":"101-abc","gatewayTransId":"gtw-9","orderId":"order-1","method":"card",
             "transStatus":1,"paypointId":42,"totalAmount":10.00,"netAmount":9.71,
             "connectorName":"fiserv","payorId":7}}
    """.trimIndent()

/**
 * An `E`, not a `D`: the service reporting a problem rather than refusing the payment, having named the
 * transaction it left behind. `SERVER_ERROR` leaves the outcome unknown, so this is the answer that both
 * asks a caller to reconcile and tells it which transaction to reconcile.
 */
internal val SERVICE_ERROR_NAMING_TRANSACTION: String =
    """
    {"code":"E0001","reason":"The processor did not answer","action":"retry",
     "data":{"paymentTransId":"101-abc"}}
    """.trimIndent()

/** A stored method, in the older envelope that route answers in. */
internal val STORED_METHOD: String =
    """
    {"isSuccess":true,"responseText":"Success",
     "responseData":{"referenceId":"tok-77","methodReferenceId":"65960bf4-46ea-42dd-ac89-250b181b3584-225810","customerId":88,
                     "resultCode":1,"resultText":"Approved"}}
    """.trimIndent()

/** A decline: a `D`-prefixed code behind a 200, which is how the service refuses a transaction. */
internal val DECLINED_TRANSACTION: String =
    """
    {"code":"D1001","reason":"Insufficient funds","explanation":"The issuer declined","action":"retry"}
    """.trimIndent()

/** The card fields as a payer would leave them, which is what the form reports. */
internal fun cardForm(
    number: String = TEST_PAN,
    expiry: String = TEST_EXPIRY_WIRE,
    securityCode: String = TEST_SECURITY_CODE,
    holderName: String = "Integration Test",
    postalCode: String = "22039",
): PayInFormValues =
    PayInFormValues(
        PayInMethodType.Card,
        mapOf(
            PayInField.CardholderName to holderName,
            PayInField.CardNumber to number,
            PayInField.CardExpiration to expiry,
            PayInField.CardSecurityCode to securityCode,
            PayInField.CardPostalCode to postalCode,
        ),
    )

/**
 * The bank fields, with the three choices empty by default.
 *
 * Empty is what a choice field holds until the payer picks from it, and the form's own bank section offers only
 * the account type of the three.
 */
internal fun bankForm(
    account: String = TEST_ACCOUNT,
    routing: String = TEST_ROUTING,
    holderName: String = "Integration Test",
    accountType: String = "",
    holderType: String = "",
    secCode: String = "",
    deviceId: String = "",
): PayInFormValues =
    PayInFormValues(
        PayInMethodType.BankAccount,
        mapOf(
            PayInField.AccountHolder to holderName,
            PayInField.RoutingNumber to routing,
            PayInField.AccountNumber to account,
            PayInField.AccountType to accountType,
            PayInField.AccountHolderType to holderType,
            PayInField.SecCode to secCode,
            PayInField.DeviceId to deviceId,
        ),
    )

/**
 * Answers each request differently, for a sequence a fixed answer cannot express.
 *
 * A retry only exists after an attempt that ended without one, so anything about resending needs the first
 * call and the second to answer differently.
 */
internal class ScriptedPayInTransport(
    private val steps: List<Result<PayabliResponse>>,
) : PayabliTransport {
    var request: PayabliRequest? = null
        private set

    /** The last request's body, copied before the client overwrites it. */
    var recordedBody: ByteArray? = null
        private set

    private var next = 0

    override suspend fun execute(request: PayabliRequest): PayabliResponse {
        this.request = request
        recordedBody = request.body?.copyOf()
        val step = steps[minOf(next, steps.lastIndex)]
        next++
        return step.getOrThrow()
    }

    override suspend fun <T> execute(
        request: PayabliRequest,
        payloadSerializer: KSerializer<T>,
    ): PayabliV2Envelope<T> = throw UnsupportedOperationException("the PayIn clients decode their own envelopes")

    companion object {
        fun answering(
            statusCode: Int,
            body: String = "",
        ): Result<PayabliResponse> =
            Result.success(PayabliResponse(statusCode, emptyMap(), body.toByteArray(Charsets.UTF_8)))

        fun failingWith(failure: Throwable): Result<PayabliResponse> = Result.failure(failure)
    }
}

/**
 * A transport that suspends until it is released, so a test can hold a submission in flight.
 *
 * `FakePayInTransport` answers immediately, which cannot show a single-flight guard or a cancellation: both are
 * about what happens while a request is outstanding.
 */
internal class GatedPayInTransport(
    private val response: PayabliResponse,
) : PayabliTransport {
    private val released = CompletableDeferred<Unit>()

    /** Every request that reached the wire, so a refused submission shows up as one that did not. */
    val sent: MutableList<PayabliRequest> = mutableListOf()

    /** Completes once a request is waiting, so a test need not guess when that is. */
    val arrived: CompletableDeferred<Unit> = CompletableDeferred()

    override suspend fun execute(request: PayabliRequest): PayabliResponse {
        sent += request
        arrived.complete(Unit)
        released.await()
        return response
    }

    override suspend fun <T> execute(
        request: PayabliRequest,
        payloadSerializer: KSerializer<T>,
    ): PayabliV2Envelope<T> = throw UnsupportedOperationException("the PayIn clients decode their own envelopes")

    fun release() {
        released.complete(Unit)
    }

    companion object {
        fun answering(
            body: String,
            statusCode: Int = 200,
        ): GatedPayInTransport =
            GatedPayInTransport(
                PayabliResponse(statusCode, emptyMap(), body.toByteArray(Charsets.UTF_8)),
            )
    }
}

/** The entry point every test here submits against. */
internal const val TEST_ENTRY_POINT: String = "merchant-entry"

/** A capture of the test amount. */
internal fun captureOf(idempotencyKey: String? = null): PayabliPayInOperation.Capture =
    PayabliPayInOperation.Capture(testOptions(idempotencyKey))

/** An authorization, which the service takes for entered card data only. */
internal fun authorizeOf(idempotencyKey: String? = null): PayabliPayInOperation.Authorize =
    PayabliPayInOperation.Authorize(testOptions(idempotencyKey))

internal fun testOptions(idempotencyKey: String? = null): PayInTransactionOptions =
    PayInTransactionOptions(paymentDetails = testDetails(), idempotencyKey = idempotencyKey)

/**
 * A card request as a host that collected the instrument itself would build it.
 *
 * The counterpart of [cardForm] for the calls that take no form. The caller owns the buffers, so a test
 * asserting they survive the call reads them back from the value this returns.
 */
internal fun cardRequest(
    idempotencyKey: String? = null,
    cardData: PayInCardData = testCardData(),
): PayInRequest = PayInRequest(PayInPaymentMethod.Card(cardData), testOptions(idempotencyKey))

/** The same card [cardForm] describes, in the public type a host builds. */
internal fun testCardData(): PayInCardData =
    PayInCardData(
        cardNumber = SensitiveDigits.ofString(TEST_PAN),
        expiry = TEST_EXPIRY,
        securityCode = SensitiveDigits.ofString(TEST_SECURITY_CODE),
        holderName = "Integration Test",
        postalCode = "22039",
    )
