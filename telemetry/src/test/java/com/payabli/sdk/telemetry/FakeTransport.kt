package com.payabli.sdk.telemetry

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
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

    override suspend fun execute(request: PayabliRequest): PayabliResponse {
        sent += request
        return answer(request)
    }

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
