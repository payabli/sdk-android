package com.payabli.sdk.core.config

/**
 * Supplies a fresh access token on demand, minted by the host app's own backend.
 *
 * Called when a token is rejected, and only from inside the SDK.
 *
 * Must mint a token rather than return a cached one. Handing back the credential that was just rejected
 * is refused, because it would be rejected again and nothing would have rotated.
 *
 * Must return a non-blank token within thirty seconds. The bound is enforced by cancelling this call, so it
 * only reaches code that is cancellation-cooperative, which means suspending rather than blocking a thread.
 *
 * Blocking has two different costs and neither is prevented by the bound. Block the thread that called this,
 * and the bound cannot be applied at all: every caller waiting on the same token is held for as long as the
 * block lasts. Move the work to another thread and block there, and callers are released on time, but the
 * blocked thread runs to completion regardless, so a hung request leaks a thread with nothing to report it.
 * An interruptible client avoids both.
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
