package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException

/**
 * A device route that was sent and answered 401 for the token it carried. Only this refusal is renewed: a session
 * whose credential is finished refuses with the same code before anything is sent, and no renewal recovers it.
 *
 * @param reason the transport's text for the refusal.
 */
internal class PinnedTokenRefusal(
    reason: String,
) : PayabliException(PayabliErrorType.TOKEN_EXPIRED, reason) {
    /**
     * The failure a caller is given once the refusal is not renewed.
     *
     * @return the expired-token failure the transport raises for any other refused request.
     */
    fun reported(): PayabliGenericException = PayabliGenericException(type, reason)
}
