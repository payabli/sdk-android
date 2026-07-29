package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.PayabliLogger
import com.payabli.sdk.core.logging.PayabliLoggers
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliRetryAfter
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

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
 * **It imposes no deadline of its own on an attempt**, beyond [RetryPolicy.totalTimeoutMillis] for the
 * operation as a whole. The transport bounds each call it makes, which is the only layer that can: a budget
 * here would wrap an opaque operation, and anything the operation does underneath, a credential refresh
 * included, would be cut short and then repeated by the next attempt.
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
        val startedAt = System.nanoTime()
        var attempt = 1
        while (true) {
            try {
                return operation(attempt)
            } catch (e: PayabliException) {
                attempt = nextAttemptOrThrow(e, attempt, policy, logger, route, startedAt)
            }
        }
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
        startedAt: Long,
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
        if (remaining != null && wait >= remaining) {
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
        startedAt: Long,
    ): Long? {
        val total = policy.totalTimeoutMillis ?: return null
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        return total - elapsed
    }

    /** Only a template is loggable; a resolved path may embed an identifier. */
    private fun routeField(route: String?): LogField =
        route?.let { LogField.safe("route", it) } ?: LogField.redacted("route", null)
}
