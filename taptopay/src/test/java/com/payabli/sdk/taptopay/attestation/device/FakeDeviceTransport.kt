package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import kotlinx.serialization.KSerializer

/**
 * A scripted [PayabliTransport] for this module.
 *
 * `:core` has one of these and it cannot be borrowed: `FakePayabliTransport` lives in `:core`'s own test
 * source set and `LoopbackServer` in a `sharedTest` directory wired into `:core`'s compilations only, and both
 * are `internal` to that module besides. Widening a published security SDK's surface to share a test double
 * is not the trade, and the cross-module fixtures module is separate work. [PayabliTransport] is public, so
 * the cost of doing without is this file.
 *
 * Deliberately no decoration chain and no auth: what this exercises is a client's own request assembly and
 * response handling. A fake that stamped an `Authorization` header would let a test come to depend on a layer
 * it is not testing.
 */
internal class FakeDeviceTransport(
    private val respond: (PayabliRequest) -> PayabliResponse,
) : PayabliTransport {
    val requests: MutableList<PayabliRequest> = mutableListOf()

    /** The single request made, failing the test rather than returning a default when that is not so. */
    val request: PayabliRequest get() = requests.single()

    /** The single request's body as text. Empty when it carried none, which is itself worth asserting. */
    val requestBody: String get() = request.body?.toString(Charsets.UTF_8).orEmpty()

    override suspend fun execute(request: PayabliRequest): PayabliResponse {
        requests += request
        return respond(request)
    }

    /**
     * Unsupported, loudly.
     *
     * The device routes carry the legacy `isSuccess` envelope, so this overload is the wrong shape for every
     * one of them and a client reaching it is a defect. Throwing names that defect at the moment it happens;
     * returning a plausible empty envelope would let the client decode nothing successfully and the test pass.
     */
    override suspend fun <T> execute(
        request: PayabliRequest,
        payloadSerializer: KSerializer<T>,
    ): PayabliV2Envelope<T> =
        throw UnsupportedOperationException(
            "the device routes use the legacy envelope; a v2 decode here means the client chose the wrong seam",
        )

    companion object {
        /** Answers every request with [body] at [statusCode], ignoring what was asked. */
        fun answering(
            body: String,
            statusCode: Int = 200,
        ): FakeDeviceTransport =
            FakeDeviceTransport { PayabliResponse(statusCode, body = body.toByteArray(Charsets.UTF_8)) }
    }
}

/** A success envelope wrapping [payload] as `responseData`. */
internal fun successEnvelope(payload: String): String =
    """{"responseText":"Success","isSuccess":true,"responseData":$payload,"pageIdentifier":"a-temporary-token"}"""

/**
 * A decline envelope, in the shape the service actually emits.
 *
 * `responseData` is typed `object` server-side, so a real decline carries the whole `ResponseApiData` surface
 * rather than the two fields that matter. The extra keys are here so these tests decode what the service
 * sends rather than a tidied version of it.
 */
internal fun declineEnvelope(
    resultCode: Int?,
    resultText: String,
): String {
    val code = resultCode?.toString() ?: "null"
    return """
        {"responseText":"Declined","isSuccess":false,
         "responseData":{"resultCode":$code,"resultText":"$resultText",
                         "referenceId":null,"authCode":null,"customerId":0}}
        """.trimIndent()
}
