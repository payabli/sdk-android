package com.payabli.sdk.core.config

/**
 * Supplies a fresh access token on demand, minted by the host app's own backend.
 *
 * Called when a token is rejected, and only from inside the SDK.
 *
 * Must mint a token rather than return a cached one. Handing back the credential that was just rejected
 * is refused, because it would be rejected again and nothing would have rotated.
 *
 * Must return a non-blank token within the refresh deadline, 10 seconds by default, and must suspend
 * rather than block a thread: a timeout cannot interrupt code that never reaches a suspension point,
 * and a blocked provider wedges the refresh and every reader waiting on it.
 *
 * Must not call back into the SDK. A re-entrant call is served the last known token rather than
 * waiting for this one to finish.
 *
 * A `fun interface` rather than a lambda type alias, so it names the type in a stack frame and stays
 * implementable from Java.
 */
public fun interface PayabliTokenProvider {
    /** Returns a freshly minted access token. Suspends; expected to make a network call. */
    public suspend fun freshToken(): String
}
