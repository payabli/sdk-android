package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import kotlin.random.Random

/**
 * How [Retry] backs off and what it is willing to retry.
 *
 * The **shape** is fixed: jittered exponential backoff, a capped maximum, bounded attempts, a per-attempt
 * timeout, and `Retry-After` honoured on 429 and 503 ahead of the computed backoff. The **numbers** are
 * deployment tuning, so the defaults below are a starting point rather than a contract.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class RetryPolicy(
    /** Total attempts, not retries: 1 means no retry at all. */
    public val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    public val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MILLIS,
    public val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
    public val multiplier: Double = DEFAULT_MULTIPLIER,
    public val maxJitterMillis: Long = DEFAULT_MAX_JITTER_MILLIS,
    /**
     * Budget for one attempt. This is the whole-resource bound; the socket-level connect and read timeouts
     * on the transport bound reads, and a call can stall indefinitely while making slow per-read progress.
     */
    public val attemptTimeoutMillis: Long = DEFAULT_ATTEMPT_TIMEOUT_MILLIS,
    /**
     * Budget for the whole operation including backoff waits. Null means unbounded, which is the default
     * because a total budget is a caller-flow concern and a wrong default would truncate a legitimate call.
     */
    public val totalTimeoutMillis: Long? = null,
    /**
     * A server asking for longer than this stops the retry rather than shortening the wait. Under-sleeping
     * would violate the limit the server just declared, and honouring an hours-long hint by sleeping is not
     * something an SDK should do either.
     */
    public val maxRetryAfterMillis: Long = DEFAULT_MAX_RETRY_AFTER_MILLIS,
    /** Injected so a test can pin it. Production never passes this. */
    public val jitter: Jitter = Jitter.Random,
    /**
     * Takes the whole exception rather than its code, so a caller can discriminate on a subtype's fields —
     * for instance a server error's `rawCode` — without this signature changing.
     */
    public val isRetryable: (PayabliException) -> Boolean = RETRYABLE_BY_CODE,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    /** Backoff before [attempt], 1-indexed. Attempt 1 does not wait. */
    public fun delayMillisFor(attempt: Int): Long {
        if (attempt <= 1) return 0
        var backoff = baseDelayMillis.toDouble()
        repeat(attempt - 2) { backoff *= multiplier }
        return backoff.toLong().coerceAtMost(maxDelayMillis) + jitter.millisUpTo(maxJitterMillis)
    }

    /** Pluggable so a test gets determinism without production writing anything. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun interface Jitter {
        public fun millisUpTo(maxMillis: Long): Long

        public companion object {
            public val Random: Jitter =
                Jitter { max -> if (max <= 0) 0 else kotlin.random.Random.nextLong(max + 1) }

            /** No jitter, for a test that asserts an exact schedule. */
            public val None: Jitter = Jitter { 0 }
        }
    }

    public companion object {
        public const val DEFAULT_MAX_ATTEMPTS: Int = 3
        public const val DEFAULT_BASE_DELAY_MILLIS: Long = 1_000
        public const val DEFAULT_MAX_DELAY_MILLIS: Long = 8_000
        public const val DEFAULT_MULTIPLIER: Double = 2.0
        public const val DEFAULT_MAX_JITTER_MILLIS: Long = 500
        public const val DEFAULT_ATTEMPT_TIMEOUT_MILLIS: Long = 15_000
        public const val DEFAULT_MAX_RETRY_AFTER_MILLIS: Long = 30_000

        /**
         * Retried because each is transient by nature. Everything absent is not retried, and three of those
         * are worth naming: a decline is authoritative and retrying risks a double charge; an expired token
         * needs refresh, which is a different mechanism, so a blind retry loops to exhaustion; and
         * [PayabliErrorCode.UNKNOWN] is an unclassified server state, where not retrying is the safe
         * default. That last one is also why a reused idempotency key needs no code of its own.
         */
        public val RETRYABLE_CODES: Set<PayabliErrorCode> =
            setOf(
                PayabliErrorCode.NETWORK_ERROR,
                PayabliErrorCode.SERVER_ERROR,
                PayabliErrorCode.RATE_LIMITED,
            )

        public val RETRYABLE_BY_CODE: (PayabliException) -> Boolean = { it.code in RETRYABLE_CODES }
    }
}
