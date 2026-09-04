package com.payabli.sdk.core.config

/**
 * Supplies a fresh access token on demand, minted by the host app's own backend.
 *
 * Called once before the first request needs a token, and again whenever one is rejected. Only from inside
 * the SDK, and it is the only way a token reaches it: nothing is passed in at configuration.
 *
 * Must mint a token rather than return a cached one. Handing back the credential that was just rejected
 * is refused, because it would be rejected again and nothing would have rotated. The first call has nothing
 * to rotate from, so any usable token answers it.
 *
 * Must not read the SDK's token while answering the first call. That value is the one this call was made to
 * produce, so it is refused rather than waited for.
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
 * May issue its own requests. While this call runs, a request against the session being refreshed carries
 * the token being replaced rather than the one about to be returned, and that holds through a chain of
 * sessions whose providers call one another.
 *
 * Must not wait on work that itself needs this refresh to finish. Such work cannot complete until the
 * refresh does, and the refresh cannot complete until this call returns.
 *
 * A `fun interface` rather than a lambda type alias, so it names the type in a stack frame and stays
 * implementable from Java.
 */
public fun interface PayabliTokenProvider {
    /** Returns a freshly minted access token. Suspends; expected to make a network call. */
    public suspend fun freshToken(): String
}
