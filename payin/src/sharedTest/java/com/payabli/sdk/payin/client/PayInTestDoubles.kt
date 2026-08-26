package com.payabli.sdk.payin.client

import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import com.payabli.sdk.payin.form.ExpiryValue
import com.payabli.sdk.payin.model.PayInAccountType
import com.payabli.sdk.payin.model.PayInAchData
import com.payabli.sdk.payin.model.PayInCardData
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.SensitiveDigits
import kotlinx.serialization.KSerializer
import java.math.BigDecimal

/**
 * A transport that answers from a script and keeps what it was asked.
 *
 * The shared `LoopbackServer` runs a real socket and answers whatever it is scripted to; this answers without
 * one, which is what a test about request shaping needs. Reach for the server when the subject is the wire.
 *
 * **The recorded body is a copy**, taken when the request is executed. The client overwrites the original once
 * the call returns, so a test reading `request.body` afterwards would see zeros — which is the behavior under
 * test, not a detail to work around.
 */
internal class FakePayInTransport(
    private val response: PayabliResponse,
    private val failure: Throwable? = null,
) : PayabliTransport {
    var request: PayabliRequest? = null

    /** How many requests reached this transport, for a test asserting a second one did not. */
    var count: Int = 0
        private set

    var recordedBody: ByteArray? = null
        private set

    /** The same array the client passed, so a test can assert it was overwritten afterwards. */
    var bodyReference: ByteArray? = null
        private set

    override suspend fun execute(request: PayabliRequest): PayabliResponse {
        count++
        this.request = request
        bodyReference = request.body
        recordedBody = request.body?.copyOf()
        failure?.let { throw it }
        return response
    }

    /**
     * Unsupported.
     *
     * This overload maps the status and decodes a v2 envelope in one step, and neither PayIn client takes that
     * path: the store route answers in the older envelope, and a transaction is classified before its payload
     * is read.
     */
    override suspend fun <T> execute(
        request: PayabliRequest,
        payloadSerializer: KSerializer<T>,
    ): PayabliV2Envelope<T> = throw UnsupportedOperationException("the PayIn clients decode their own envelopes")

    /** The request body as text, from the copy taken before the client wiped it. */
    fun bodyText(): String = recordedBody?.toString(Charsets.UTF_8) ?: ""

    companion object {
        fun answering(
            body: String,
            statusCode: Int = 200,
        ): FakePayInTransport =
            FakePayInTransport(
                PayabliResponse(statusCode, emptyMap(), body.toByteArray(Charsets.UTF_8)),
            )

        /** Records the request, then throws, which is the path the body wipe depends on. */
        fun failingWith(failure: Throwable): FakePayInTransport =
            FakePayInTransport(
                PayabliResponse(200, emptyMap(), ByteArray(0)),
                failure = failure,
            )
    }
}

/** The test card, which passes the Luhn check. */
internal const val TEST_PAN: String = "4111111111111111"

internal const val TEST_SECURITY_CODE: String = "999"

/** A valid ABA routing number: US Bank, Minnesota, as the service's own test payloads use. */
internal const val TEST_ROUTING: String = "122105278"

internal const val TEST_ACCOUNT: String = "00003400000"

/**
 * Far enough ahead that the calendar cannot expire it, and derived rather than written down.
 *
 * Both clients validate the expiry against `ExpiryValue.today()` and there is no clock to inject, so a fixed
 * year here is a date on which every round-trip, wipe and logging test in this package turns red without any
 * change having been made. [TEST_EXPIRY_WIRE] is derived from the same value, and `MM/YY` itself is pinned by
 * a test on `format()`, which needs no clock and so can assert the literal.
 */
internal val TEST_EXPIRY: ExpiryValue = ExpiryValue(12, ExpiryValue.today().year + 5)

/** What [TEST_EXPIRY] looks like on the wire. */
internal val TEST_EXPIRY_WIRE: String = TEST_EXPIRY.format()

internal fun testCard(
    pan: String = TEST_PAN,
    securityCode: String = TEST_SECURITY_CODE,
    holderName: String = "Integration Test",
    postalCode: String = "22039",
    expiry: ExpiryValue = TEST_EXPIRY,
): PayInCardData =
    PayInCardData(
        cardNumber = SensitiveDigits.ofString(pan),
        expiry = expiry,
        securityCode = SensitiveDigits.ofString(securityCode),
        holderName = holderName,
        postalCode = postalCode,
    )

internal fun testAccount(
    account: String = TEST_ACCOUNT,
    routing: String = TEST_ROUTING,
    holderName: String = "Integration Test",
    accountType: PayInAccountType = PayInAccountType.Checking,
): PayInAchData =
    PayInAchData(
        accountNumber = SensitiveDigits.ofString(account),
        routingNumber = routing,
        accountType = accountType,
        holderName = holderName,
    )

internal fun testDetails(
    total: String = "10",
    fee: String? = null,
): PayInPaymentDetails =
    PayInPaymentDetails(
        totalAmount = BigDecimal(total),
        serviceFee = fee?.let(::BigDecimal),
        currency = "USD",
    )
