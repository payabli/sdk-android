package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.core.model.PayabliException

/**
 * A device route that was sent and answered 401 for the token it carried. Only this refusal is renewed: a session
 * whose credential is finished refuses with the same code before anything is sent, and no renewal recovers it.
 *
 * @param transportFailure the transport's failure for the refusal.
 */
internal class PinnedTokenRefusal(
    private val transportFailure: PayabliException,
) : PayabliException(transportFailure.type, transportFailure.reason) {
    override val httpStatus: Int? get() = transportFailure.httpStatus

    override val rawCode: String? get() = transportFailure.rawCode

    /**
     * The failure a caller is given once the refusal is not renewed.
     *
     * @return the expired-token failure the transport raised for the refusal.
     */
    fun reported(): PayabliException = transportFailure
}
