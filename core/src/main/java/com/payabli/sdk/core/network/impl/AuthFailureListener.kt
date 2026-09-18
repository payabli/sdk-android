package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.model.PayabliException

/**
 * Reports that auth is beyond recovery from inside the SDK, so the session it belongs to is finished.
 *
 * A fact from the layer that establishes it, rather than an inference downstream from an error code. The
 * inference does not work: a credential-pinned route's 401 also reaches a caller of the typed overload as
 * `TOKEN_EXPIRED`, through [PayabliHttpErrors.from][com.payabli.sdk.core.network.PayabliHttpErrors.from] —
 * `AuthenticatedTransport` returns that response unretried rather than raising anything, so this listener
 * is never told about it. Nothing about the code alone says whether a `TOKEN_EXPIRED` a caller sees is one
 * blip on a session that still works for the next request or a session that is genuinely finished, where a
 * freshly minted token was refused again. Only the layer that ran the refresh knows which of those
 * happened.
 *
 * A provider that timed out or threw is not this at all: that surfaces as `TOKEN_PROVIDER_FAILED`, a code
 * an integrator can branch on without needing this listener, and it never reaches here —
 * [PayabliAuth][com.payabli.sdk.core.auth.PayabliAuth] releases the claim on a provider failure without
 * marking itself finished, so the next request gets a fresh attempt.
 */
internal fun interface AuthFailureListener {
    fun onUnrecoverable(failure: PayabliException)
}
