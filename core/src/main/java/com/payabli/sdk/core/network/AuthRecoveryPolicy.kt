package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED

private const val REASON_REFRESH_REJECTED = "the refreshed token was rejected as well"

/**
 * Credential rejection, as opposed to [RetryPolicy]'s transient failures. Layered
 * `Service -> Retry -> AuthRecovery -> Transport`, and `AuthenticatedTransport` runs it. Outside the retry
 * the operation has already thrown, and [isCredentialRejection] would have no [PayabliResponse] to read.
 *
 * **Classification is extensible, the terminal mapping is not.** A subclass may widen what counts as a
 * rejection; it cannot change what a surviving one becomes. [exhausted] is deliberately not `open`, because a
 * subclass returning a retryable code would make the outer `Retry` treat a terminal credential failure as
 * transient and run further rejection, refresh and replay cycles instead of stopping. A token minted seconds
 * ago and refused again is an authorization fact, not a transient one.
 *
 * **Narrowing is not done here.** A route that cannot survive a refresh says so on its own requests, through
 * `PayabliRequest.isCredentialPinned`, which `AuthenticatedTransport` reads above this. A subclass narrowing
 * [isCredentialRejection] instead would be overridden by the next subclass that widens it.
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

    /** Carries no server text: a 401 body is not ours to relay. Final, for the reason in the class note. */
    public fun exhausted(): PayabliGenericException =
        PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, REASON_REFRESH_REJECTED)
}
