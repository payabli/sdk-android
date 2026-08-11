package com.payabli.sdk.payin.client

import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.core.logging.SdkLogger
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
 * `:core`'s `LoopbackServer` is not reachable from here: it lives in `core/src/sharedTest` and leans on
 * `internal` `:core` fixtures. `:taptopay` has its own double.
 *
 * **The recorded body is a copy**, taken when the request is executed. The client overwrites the original once
 * the call returns, so a test reading `request.body` afterwards would see zeros — which is the behaviour under
 * test, not a detail to work around.
 */
internal class FakePayInTransport(
    private val response: PayabliResponse,
    private val failure: Throwable? = null,
) : PayabliTransport {
    var request: PayabliRequest? = null
        private set

    var recordedBody: ByteArray? = null
        private set

    /** The same array the client passed, so a test can assert it was overwritten afterwards. */
    var bodyReference: ByteArray? = null
        private set

    override suspend fun execute(request: PayabliRequest): PayabliResponse {
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

/**
 * A logger that keeps what it was given.
 *
 * `:core`'s recording sink is internal to `:core`, so this implements the public [SdkLogger] instead: two
 * members, which is what that interface documents as the cost of a fake.
 */
internal class RecordingLogger : SdkLogger {
    class Record(
        val level: LogLevel,
        val fields: List<LogField>,
        val message: String,
    )

    val records: MutableList<Record> = mutableListOf()

    /** Everything, so a test sees every record the SDK writes. */
    override fun isLoggable(level: LogLevel): Boolean = true

    override fun log(
        level: LogLevel,
        fields: List<LogField>,
        throwable: Throwable?,
        message: () -> String,
    ) {
        records += Record(level, fields, message())
    }

    /** Every field value and every message, flattened, for asserting that a value never appears. */
    fun everythingWritten(): String =
        records.joinToString(" ") { record ->
            record.message + " " + record.fields.joinToString(" ") { it.toString() }
        }
}

/** The test card, which passes the Luhn check. */
internal const val TEST_PAN: String = "4111111111111111"

internal const val TEST_SECURITY_CODE: String = "999"

/** A valid ABA routing number: US Bank, Minnesota, as the service's own test payloads use. */
internal const val TEST_ROUTING: String = "122105278"

internal const val TEST_ACCOUNT: String = "00003400000"

internal fun testCard(
    pan: String = TEST_PAN,
    securityCode: String = TEST_SECURITY_CODE,
    holderName: String = "Integration Test",
    postalCode: String = "22039",
    expiry: ExpiryValue = ExpiryValue(12, 2030),
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
