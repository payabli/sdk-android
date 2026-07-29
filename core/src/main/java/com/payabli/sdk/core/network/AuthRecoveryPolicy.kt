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
 * Fixed rather than tunable: one 401, one refresh, one replay, then [PayabliErrorCode.TOKEN_EXPIRED]. A token
 * minted seconds ago and refused again is an authorization fact, not a transient one.
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
