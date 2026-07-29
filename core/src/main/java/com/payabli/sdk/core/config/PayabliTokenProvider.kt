package com.payabli.sdk.core.config

/**
 * Supplies a fresh access token on demand, minted by the host app's own backend.
 *
 * The SDK invokes this when a request is rejected as unauthorized. Anything thrown from it surfaces to
 * the original caller as a token failure rather than as the provider's own exception type.
 *
 * A `fun interface` rather than a type alias for a lambda: it names the type in a stack frame, and it
 * keeps the contract implementable from Java.
 */
public fun interface PayabliTokenProvider {
    /** Returns a freshly minted access token. Suspends; expected to make a network call. */
    public suspend fun freshToken(): String
}
