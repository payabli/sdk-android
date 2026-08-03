package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.model.PayabliException

/**
 * Reports that auth is beyond recovery from inside the SDK, so the session it belongs to is finished.
 *
 * A fact from the layer that establishes it, rather than an inference downstream from an error code. The
 * inference does not work, because `TOKEN_EXPIRED` is raised by both kinds of failure and says nothing about
 * which: a provider that timed out or threw once, where the next request may well succeed, and a session
 * that is genuinely finished, where no provider was supplied or a freshly minted token was refused again.
 * Only the layer that ran the refresh knows which of those happened.
 */
internal fun interface AuthFailureListener {
    fun onUnrecoverable(failure: PayabliException)
}
