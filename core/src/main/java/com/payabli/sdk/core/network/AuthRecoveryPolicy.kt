package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException

private const val HTTP_UNAUTHORIZED = 401
private const val REASON_REFRESH_REJECTED = "the refreshed token was rejected as well"

/**
 * Credential rejection, as opposed to [RetryPolicy]'s transient failures. Layered
 * `Service -> Retry -> AuthRecovery -> Transport`, and `AuthenticatedTransport` runs it.
 *
 * Inside retry rather than around it, which is where a 401 is still a [PayabliResponse]: outside, the
 * operation has already turned it into a thrown error and [isCredentialRejection] would have nothing to read.
 *
 * **The default is fixed to 401**, and a subclass may widen it: one rejection, one refresh, one replay, then
 * [PayabliErrorCode.TOKEN_EXPIRED]. A token minted seconds ago and refused again is an authorization fact,
 * not a transient one.
 *
 * Widening changes what gets refreshed, never what gets replayed. `AuthenticatedTransport` decides that
 * separately, and only replays when the status was 401 or the method is idempotent, so a widened status on a
 * POST refreshes without a replay. A policy cannot authorize a replay its status does not justify.
 *
 * `@RestrictTo` rather than `internal` so a capability in its own artifact can subclass it. That is Lint, not
 * access control, so nothing credential-bearing belongs here.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public open class AuthRecoveryPolicy {
    /** Only a 401. A 410 is a burned session and a 402 is a decline; no refresh fixes either. */
    public open fun isCredentialRejection(response: PayabliResponse): Boolean = response.statusCode == HTTP_UNAUTHORIZED

    /** Carries no server text: a 401 body is not ours to relay. */
    public open fun exhausted(): PayabliGenericException =
        PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, REASON_REFRESH_REJECTED)
}
