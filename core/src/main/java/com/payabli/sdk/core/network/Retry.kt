package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.PayabliLogger
import com.payabli.sdk.core.logging.PayabliLoggers
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.model.PayabliRetryAfter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Runs an operation with bounded, jittered retries.
 *
 * **Applied per call site, never to every request**, because no write may be retried outside whatever
 * makes it duplicate-safe. The operation must raise a non-2xx itself, since the transport does not:
 *
 * ```kotlin
 * Retry.run(route = "/api/v2/MoneyIn/update/{id}") {
 *     val response = transport.execute(request)
 *     PayabliHttpErrors.from(response)?.let { throw it }
 *     response
 * }
 * ```
 *
 * Outside the transport rather than a decoration inside it, so each attempt re-enters
 * [PayabliTransport.execute] and re-runs the decoration chain.
 *
 * No attempt gets a deadline of its own. [RetryPolicy.totalTimeoutMillis], when set, is one deadline for the
 * whole operation; each call is otherwise bounded by the transport.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object Retry {
    /**
     * Runs [operation], retrying while [RetryPolicy.isRetryable] accepts the failure and attempts remain.
     *
     * Rethrows the **underlying** error on exhaustion rather than wrapping it, so a caller sees the real
     * cause. A non-[PayabliException] propagates untouched and unretried; in particular a
     * `CancellationException` is never caught, so cancellation is never converted into a completion.
     *
     * @param route the route template for logs. Never a resolved path, which may embed an identifier.
     */
    public suspend fun <T> run(
        route: String? = null,
        policy: RetryPolicy = RetryPolicy(),
        logger: PayabliLogger = PayabliLoggers.of(LogCategory.NETWORK),
        operation: suspend (attempt: Int) -> T,
    ): T {
        // One origin, with every remainder derived from it, so the attempt bound and the backoff gate read
        // the same number.
        val startedAt = TimeSource.Monotonic.markNow()
        val total = policy.totalTimeoutMillis?.milliseconds
        var attempt = 1
        while (true) {
            val remaining = total?.minus(startedAt.elapsedNow())
            if (remaining != null && remaining <= Duration.ZERO) {
                currentCoroutineContext().ensureActive()
                throw budgetExhausted(logger, route, policy)
            }
            val outcome =
                try {
                    // Nothing catches a CancellationException here, so the caller's cancellation and the
                    // operation's own deadline both pass through. Result distinguishes a null return.
                    if (remaining == null) {
                        Result.success(operation(attempt))
                    } else {
                        withTimeoutOrNull(remaining) { Result.success(operation(attempt)) }
                    }
                } catch (e: PayabliException) {
                    attempt = nextAttemptOrThrow(e, attempt, policy, logger, route, startedAt)
                    continue
                }
            // Outside the catch, so a budget expiry is never offered to the retry decision.
            if (outcome == null) {
                // Cancellation can land between the null and this throw with nothing suspending in between,
                // and a cancelled caller must not be told its operation timed out.
                currentCoroutineContext().ensureActive()
                throw budgetExhausted(logger, route, policy)
            }
            return outcome.getOrThrow()
        }
    }

    /** The whole operation ran out of time. Terminal: every throw site sits outside the retry decision. */
    private fun budgetExhausted(
        logger: PayabliLogger,
        route: String?,
        policy: RetryPolicy,
    ): PayabliException {
        logger.warn(
            routeField(route),
            LogField.safe("totalTimeoutMs", policy.totalTimeoutMillis ?: -1L),
        ) { "total budget exhausted mid-attempt; not retrying" }
        return PayabliGenericException(PayabliErrorCode.NETWORK_ERROR, REASON_TOTAL_TIMEOUT)
    }

    /**
     * Waits and returns the next attempt number, or rethrows [failure] when there is no next attempt.
     *
     * Every reason to stop funnels through here so each one throws the underlying error rather than
     * something synthesised.
     */
    private suspend fun nextAttemptOrThrow(
        failure: PayabliException,
        attempt: Int,
        policy: RetryPolicy,
        logger: PayabliLogger,
        route: String?,
        startedAt: TimeMark,
    ): Int {
        if (attempt >= policy.maxAttempts || !policy.isRetryable(failure)) throw failure

        val serverHint = (failure as? PayabliRetryAfter)?.retryAfterMillis
        if (serverHint != null && serverHint > policy.maxRetryAfterMillis) {
            // Shortening it would violate the limit the server just declared, so stop instead.
            logger.warn(
                routeField(route),
                LogField.safe("retryAfter", serverHint),
                LogField.safe("errorCode", failure.code),
            ) { "retry-after exceeds the ceiling; not retrying" }
            throw failure
        }

        // The server's instruction wins over the computed backoff (RFC 9110).
        val wait = serverHint ?: policy.delayMillisFor(attempt + 1)
        val remaining = remainingBudget(policy, startedAt)
        if (remaining != null && wait.milliseconds >= remaining) {
            // Sleeping past the total budget only delays the same failure.
            logger.warn(routeField(route), LogField.safe("errorCode", failure.code)) {
                "total budget exhausted; not retrying"
            }
            throw failure
        }

        logger.debug(
            routeField(route),
            LogField.safe("attempt", attempt),
            LogField.safe("maxAttempts", policy.maxAttempts),
            LogField.safe("retryable", true),
            LogField.safe("errorCode", failure.code),
            LogField.safe("retryAfter", serverHint ?: -1L),
            LogField.safe("timeoutMs", wait),
        ) { "retrying" }

        delay(wait.milliseconds)
        return attempt + 1
    }

    /** Null when no total budget is set. Monotonic, so a wall-clock change cannot distort it. */
    private fun remainingBudget(
        policy: RetryPolicy,
        startedAt: TimeMark,
    ): Duration? = policy.totalTimeoutMillis?.milliseconds?.minus(startedAt.elapsedNow())

    /** Only a template is loggable; a resolved path may embed an identifier. */
    private fun routeField(route: String?): LogField =
        route?.let { LogField.safe("route", it) } ?: LogField.redacted("route", null)

    private const val REASON_TOTAL_TIMEOUT = "Operation exceeded its total timeout"
}
