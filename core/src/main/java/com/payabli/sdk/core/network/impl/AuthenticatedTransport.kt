package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.network.AuthRecoveryPolicy
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer

/**
 * Runs [AuthRecoveryPolicy]: one 401, one refresh, one replay, second 401 terminal. Mechanism only, so what
 * counts as a rejection and what a surviving one becomes are the policy's.
 *
 * It does not inject the bearer; `BearerDecoration` does, inside [base]'s chain. So a forgotten wrapper costs
 * a recovery, never authentication.
 *
 * It replays any method including POST, which is safe on a 401 alone: the request was refused before
 * processing, so a replay cannot double-charge. No other status carries that argument.
 *
 * **It only sees what arrives as an HTTP 401.** Card-present device routes answer 200 with the status in the
 * envelope, so this never fires there, which is correct: those routes pin the token captured at attestation
 * and a refresh would rotate it out of the match. Widen or narrow the policy deliberately, never rely on the
 * status happening not to be 401.
 */
internal class AuthenticatedTransport(
    private val base: PayabliTransport,
    private val auth: PayabliAuth,
    private val recovery: AuthRecoveryPolicy = AuthRecoveryPolicy(),
) : PayabliTransport {
    override suspend fun execute(request: PayabliRequest): PayabliResponse {
        val stamped = SentToken()
        val first = withContext(stamped) { base.execute(request) }
        if (!recovery.isCredentialRejection(first)) return first

        // The token the chain stamped, not one this class read earlier: a rotation between the two reads would
        // make them disagree, and reporting the earlier one replays the credential just refused.
        auth.invalidateAndRefresh(stamped.value ?: auth.accessToken())
        // No re-authorizing: re-entering the transport re-runs the chain, which reads the token again.
        val second = withContext(SentToken()) { base.execute(request) }
        if (recovery.isCredentialRejection(second)) throw recovery.exhausted()
        return second
    }

    /** Not delegated: [base]'s overload maps a 401 to a typed error before this could recover it. */
    override suspend fun <T> execute(
        request: PayabliRequest,
        payloadSerializer: KSerializer<T>,
    ): PayabliV2Envelope<T> = execute(request).asV2Envelope(payloadSerializer)
}
