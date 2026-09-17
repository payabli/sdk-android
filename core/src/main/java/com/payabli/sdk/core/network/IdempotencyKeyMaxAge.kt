package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * How long a money-moving attempt's idempotency key is worth resending.
 *
 * Derived to land past the point the service stops recognising the key: the service's two-minute
 * duplicate window, plus the transport's whole-call budget doubled for the one credential replay it
 * may perform. The clock starts at reservation, which is earlier than the service's does.
 *
 * Erring long costs nothing material — a forgotten key and an unseen one are executed alike. Erring
 * short forfeits the refusal, which is the only thing on these paths that stops a second charge.
 *
 * Here rather than in a capability module because both card-not-present and card-present hold a key
 * for the same window, and a value mirrored by hand in two modules is one that drifts.
 */
@get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public val IDEMPOTENCY_KEY_MAX_AGE: Duration = 3.minutes
