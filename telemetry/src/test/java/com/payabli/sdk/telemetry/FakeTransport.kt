package com.payabli.sdk.telemetry

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.KSerializer

/**
 * A transport that records what it was asked to send, and answers however the test says.
 *
 * The point of asserting on the captured request rather than on a socket is that the wire shape is what the
 * far side holds this client to, and every one of its rules is a silent drop.
 */
internal class FakeTransport(
    private val answer: (PayabliRequest) -> PayabliResponse = { accepted() },
) : PayabliTransport {
    val sent = mutableListOf<PayabliRequest>()

    /**
     * Holds every call inside `execute` until it is completed.
     *
     * A stalled upload is the condition the client's bounds are for, and it cannot be shown with a transport
     * that answers: what matters is what the client does while a request is in flight and not returning.
     */
    private var gate: CompletableDeferred<Unit>? = null

    override suspend fun execute(request: PayabliRequest): PayabliResponse {
        sent += request
        gate?.await()
        return answer(request)
    }

    /** Parks every call from here on, as an offline or very slow host does. */
    fun stall() {
        gate = CompletableDeferred()
    }

    /** Lets the parked calls finish, so a test can end without a live coroutine. */
    fun release() {
        gate?.complete(Unit)
        gate = null
    }

    /** How many events reached the wire across every request, which is the only count that matters. */
    fun eventsSent(): Int = sent.indices.sumOf { bodyAsText(it).split("\"schemaVersion\"").size - 1 }

    override suspend fun <T> execute(
        request: PayabliRequest,
        payloadSerializer: KSerializer<T>,
    ): PayabliV2Envelope<T> = throw UnsupportedOperationException("telemetry does not decode a payload")

    fun bodyAsText(index: Int = 0): String = sent[index].body!!.toString(Charsets.UTF_8)

    companion object {
        fun accepted(): PayabliResponse = PayabliResponse(statusCode = 202, headers = emptyMap(), body = ByteArray(0))

        fun refusing(statusCode: Int): FakeTransport =
            FakeTransport { PayabliResponse(statusCode = statusCode, headers = emptyMap(), body = ByteArray(0)) }

        fun failing(): FakeTransport =
            FakeTransport {
                throw PayabliGenericException(PayabliErrorCode.NETWORK_ERROR, "the host could not be reached")
            }
    }
}
