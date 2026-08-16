package com.payabli.sdk.taptopay.network

import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import kotlinx.serialization.KSerializer

/**
 * A scripted [PayabliTransport] that answers a **sequence**.
 *
 * Separate from the device package's double, which answers one call at a time because every route there is
 * a stateless one-shot. Closing a transaction is retried, so what a test needs here is what the second and
 * third attempts see, and a sequence is the only way to say that. Running out of answers fails the test
 * rather than repeating the last one: a retry test that silently gets a fourth answer proves nothing about
 * the bound.
 *
 * `:core` has a double of its own and it cannot be borrowed — it lives in `:core`'s test source set and is
 * `internal` to that module. [PayabliTransport] is public, so the cost of doing without is this file.
 *
 * Deliberately no decoration chain and no auth: what this exercises is a client's own request assembly and
 * response handling. A fake that stamped an `Authorization` header would let a test come to depend on a
 * layer it is not testing.
 */
internal class FakeTransactionTransport(
    private vararg val answers: PayabliResponse,
) : PayabliTransport {
    val requests: MutableList<PayabliRequest> = mutableListOf()

    /** The single request made, failing the test rather than returning a default when that is not so. */
    val request: PayabliRequest get() = requests.single()

    /** A request's body as text. Empty when it carried none, which is itself worth asserting. */
    fun bodyText(index: Int = 0): String = requests[index].body?.toString(Charsets.UTF_8).orEmpty()

    override suspend fun execute(request: PayabliRequest): PayabliResponse {
        val index = requests.size
        requests += request
        return answers.getOrNull(index)
            ?: throw AssertionError("attempt ${index + 1} was made and the script holds ${answers.size} answers")
    }

    /**
     * Unsupported, loudly.
     *
     * Nothing in this module decodes through the transport's typed overload: every client here reads the
     * status, then the envelope, then the payload, in that order and in its own code, because the two
     * envelope shapes this SDK meets are classified differently. A client reaching this is a defect, and
     * throwing names it at the moment it happens where returning a plausible empty envelope would let the
     * client decode nothing successfully and the test pass.
     */
    override suspend fun <T> execute(
        request: PayabliRequest,
        payloadSerializer: KSerializer<T>,
    ): PayabliV2Envelope<T> =
        throw UnsupportedOperationException(
            "this module classifies its own envelopes; a decode here means the client chose the wrong seam",
        )

    companion object {
        fun answer(
            body: String,
            statusCode: Int = 200,
        ): PayabliResponse = PayabliResponse(statusCode, body = body.toByteArray(Charsets.UTF_8))
    }
}

/** An approval carrying [payload] as `data`. */
internal fun approved(payload: String): String = """{"code":"A01","reason":"Success","data":$payload}"""

/** A refusal: a `D` code is the payment being declined. */
internal fun declined(
    code: String = "D01",
    reason: String = "Declined",
): String = """{"code":"$code","reason":"$reason"}"""
