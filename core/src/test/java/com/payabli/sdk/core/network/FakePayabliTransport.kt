package com.payabli.sdk.core.network

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * Scripted [PayabliTransport] for unit tests: enqueue responses, assert on what was recorded.
 *
 * Lives in `:core`'s own test source set because there is no shared fixtures module yet. When one lands,
 * this is the first thing that should move into it.
 *
 * **It deliberately does not apply the decoration chain.** Endpoint-client tests must not come to depend
 * on decoration having happened, or a later "fix" here would quietly turn this fake into a second
 * sanctioned transport. Decoration is `PayabliService`'s job and is tested there.
 */
internal class FakePayabliTransport : PayabliTransport {
    private val queued = mutableListOf<PayabliResponse>()
    private var nextIndex = 0

    /** Every request the fake was asked to execute, in order. */
    val recorded: MutableList<PayabliRequest> = mutableListOf()

    fun enqueue(
        statusCode: Int = 200,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
    ): FakePayabliTransport {
        queued += PayabliResponse(statusCode, headers, body.toByteArray(Charsets.UTF_8))
        return this
    }

    override suspend fun execute(request: PayabliRequest): PayabliResponse {
        recorded += request
        // Indexed rather than removeFirst(): the Kotlin extension collides with java.util.List's
        // own removeFirst() on newer platform versions.
        check(nextIndex < queued.size) { "no response enqueued for $request" }
        return queued[nextIndex++]
    }

    override suspend fun <T> execute(
        request: PayabliRequest,
        payloadSerializer: KSerializer<T>,
    ): PayabliV2Envelope<T> {
        val response = execute(request)
        // Mirrors PayabliService: a fake that skipped the status mapping would let a contract test pass
        // against behaviour the real transport does not have.
        PayabliHttpErrors.from(response)?.let { throw it }
        return try {
            PayabliJson.format.decodeFromString(
                PayabliV2Envelope.serializer(payloadSerializer),
                response.bodyAsText(),
            )
        } catch (e: SerializationException) {
            throw PayabliGenericException(
                PayabliErrorCode.DECODING_ERROR,
                "Failed to decode response envelope",
                cause = e,
            )
        }
    }
}
