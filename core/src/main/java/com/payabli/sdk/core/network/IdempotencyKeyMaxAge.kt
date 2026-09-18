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
 * Erring long keeps a key that the service has forgotten. Money-in then mints when its map is full;
 * card-present refuses a new entry point at its cap — so the cost of erring long is a refused charge
 * there, not free memory. Erring short forfeits the refusal on both paths, which is the direction that
 * can take the money twice.
 *
 * Here rather than in a capability module because both card-not-present and card-present hold a key
 * for the same window, and a value mirrored by hand in two modules is one that drifts.
 */
@get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public val IDEMPOTENCY_KEY_MAX_AGE: Duration = 3.minutes
