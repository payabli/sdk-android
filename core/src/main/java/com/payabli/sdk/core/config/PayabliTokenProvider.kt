package com.payabli.sdk.core.config

/**
 * Supplies a fresh access token on demand, minted by the host app's own backend.
 *
 * Called when a token is rejected, and only from inside the SDK.
 *
 * Must return a non-blank token within the refresh deadline, and must not call back into the SDK: a
 * re-entrant call is served the last known token rather than waiting for this one to finish.
 *
 * A `fun interface` rather than a lambda type alias, so it names the type in a stack frame and stays
 * implementable from Java.
 */
public fun interface PayabliTokenProvider {
    /** Returns a freshly minted access token. Suspends; expected to make a network call. */
    public suspend fun freshToken(): String
}
