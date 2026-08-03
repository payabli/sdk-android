package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.model.PayabliException

/**
 * Reports that auth is beyond recovery from inside the SDK, so the session it belongs to is finished.
 *
 * A fact from the layer that establishes it, rather than an inference downstream from an error code. The
 * inference does not work: `PayabliHttpErrors` maps every 401 to `TOKEN_EXPIRED`, including one whose refresh
 * succeeded but whose non-idempotent request was not replayed, and that session is healthy. Only
 * `AuthenticatedTransport` knows whether the refresh happened and what it produced.
 */
internal fun interface AuthFailureListener {
    fun onUnrecoverable(failure: PayabliException)
}
