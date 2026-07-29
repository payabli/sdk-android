@file:OptIn(ExperimentalCoroutinesApi::class)

package com.payabli.sdk.core.network

import com.payabli.sdk.core.auth.DEFAULT_PROVIDER_TIMEOUT_MILLIS
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultPayabliLogger
import com.payabli.sdk.core.model.PayabliDeclineException
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.model.PayabliRateLimitException
import com.payabli.sdk.core.model.PayabliServerException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timing is asserted on `TestScope.currentTime`, which is exact because `runTest` runs `delay` and
 * `withTimeout` in virtual time. That only holds because [Retry] never switches dispatcher.
 */
class RetryTest {
    private val sink = RecordingLogSink()
    private val logger = DefaultPayabliLogger(LogCategory.NETWORK, sink)

    /** No jitter, so every schedule below is an exact number rather than a range. */
    private fun policy(
        maxAttempts: Int = 3,
        totalTimeoutMillis: Long? = null,
        attemptTimeoutMillis: Long = RetryPolicy.DEFAULT_ATTEMPT_TIMEOUT_MILLIS,
        maxRetryAfterMillis: Long = RetryPolicy.DEFAULT_MAX_RETRY_AFTER_MILLIS,
    ) = RetryPolicy(
        maxAttempts = maxAttempts,
        totalTimeoutMillis = totalTimeoutMillis,
        attemptTimeoutMillis = attemptTimeoutMillis,
        maxRetryAfterMillis = maxRetryAfterMillis,
        jitter = RetryPolicy.Jitter.None,
    )

    private fun serverError(retryAfterMillis: Long? = null) =
        PayabliServerException(httpStatus = 503, retryAfterMillis = retryAfterMillis)

    private suspend fun <T> failureFrom(block: suspend () -> T): PayabliException {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
        return thrown as PayabliException
    }

    @Test
    fun `a flaky operation that fails then succeeds is retried and returns success`() =
        runTest {
            var attempts = 0

            val result =
                Retry.run(policy = policy(), logger = logger) {
                    attempts++
                    if (attempts == 1) throw serverError()
                    "ok"
                }

            assertEquals("ok", result)
            assertEquals(2, attempts)
        }

    @Test
    fun `an always-failing operation exhausts the policy and throws the underlying error`() =
        runTest {
            var attempts = 0
            val underlying = serverError()

            val thrown =
                failureFrom {
                    Retry.run(policy = policy(maxAttempts = 3), logger = logger) {
                        attempts++
                        throw underlying
                    }
                }

            // The underlying error, not a wrapper: the ticket's acceptance criterion.
            assertSame(underlying, thrown)
            assertEquals(3, attempts)
        }

    @Test
    fun `backoff is exponential and capped`() =
        runTest {
            val policy = policy(maxAttempts = 5)
            assertEquals(0L, policy.delayMillisFor(1))
            assertEquals(1_000L, policy.delayMillisFor(2))
            assertEquals(2_000L, policy.delayMillisFor(3))
            assertEquals(4_000L, policy.delayMillisFor(4))
            assertEquals(8_000L, policy.delayMillisFor(5))
            // Capped, not 16s.
            assertEquals(8_000L, policy.delayMillisFor(6))
        }

    @Test
    fun `the total wait follows the computed backoff`() =
        runTest {
            val started = currentTime

            failureFrom {
                Retry.run(policy = policy(maxAttempts = 3), logger = logger) { throw serverError() }
            }

            // 1s before attempt 2, 2s before attempt 3.
            assertEquals(3_000L, currentTime - started)
        }

    @Test
    fun `a server Retry-After beats the computed backoff`() =
        runTest {
            val started = currentTime

            failureFrom {
                Retry.run(policy = policy(maxAttempts = 2), logger = logger) {
                    throw serverError(retryAfterMillis = 5_000)
                }
            }

            // 5s from the header, not the 1s the policy would have computed.
            assertEquals(5_000L, currentTime - started)
        }

    @Test
    fun `a 429 is retryable and carries its own hint`() =
        runTest {
            val started = currentTime
            var attempts = 0

            val result =
                Retry.run(policy = policy(), logger = logger) {
                    attempts++
                    if (attempts == 1) throw PayabliRateLimitException(retryAfterMillis = 2_500)
                    "ok"
                }

            assertEquals("ok", result)
            assertEquals(2_500L, currentTime - started)
        }

    @Test
    fun `a Retry-After beyond the ceiling stops rather than under-sleeping`() =
        runTest {
            val started = currentTime
            var attempts = 0

            failureFrom {
                Retry.run(policy = policy(maxRetryAfterMillis = 30_000), logger = logger) {
                    attempts++
                    throw PayabliRateLimitException(retryAfterMillis = 3_600_000)
                }
            }

            // Sleeping less would violate the limit the server declared, so it does not retry at all.
            assertEquals(1, attempts)
            assertEquals(0L, currentTime - started)
        }

    @Test
    fun `a decline is never retried`() =
        runTest {
            var attempts = 0
            val decline = PayabliDeclineException(rawCode = "D0329")

            val thrown =
                failureFrom {
                    Retry.run(policy = policy(), logger = logger) {
                        attempts++
                        throw decline
                    }
                }

            assertSame(decline, thrown)
            assertEquals(1, attempts)
        }

    @Test
    fun `every non-retryable code stops on the first attempt`() =
        runTest {
            PayabliErrorCode.entries
                .filterNot { it in RetryPolicy.RETRYABLE_CODES }
                .forEach { code ->
                    var attempts = 0
                    failureFrom {
                        Retry.run(policy = policy(), logger = logger) {
                            attempts++
                            throw PayabliGenericException(code, "nope")
                        }
                    }
                    assertEquals("code $code", 1, attempts)
                }
        }

    @Test
    fun `the retryable set is exactly the three transient codes`() {
        assertEquals(
            setOf(
                PayabliErrorCode.NETWORK_ERROR,
                PayabliErrorCode.SERVER_ERROR,
                PayabliErrorCode.RATE_LIMITED,
            ),
            RetryPolicy.RETRYABLE_CODES,
        )
    }

    @Test
    fun `an attempt that overruns its budget becomes a retryable network error`() =
        runTest {
            var attempts = 0

            val result =
                Retry.run(policy = policy(attemptTimeoutMillis = 1_000), logger = logger) {
                    attempts++
                    if (attempts == 1) delay(5_000)
                    "ok"
                }

            assertEquals("ok", result)
            assertEquals(2, attempts)
        }

    @Test
    fun `the total budget declines a further attempt and throws the last error`() =
        runTest {
            var attempts = 0
            val underlying = serverError()

            val thrown =
                failureFrom {
                    Retry.run(policy = policy(maxAttempts = 5, totalTimeoutMillis = 1_500), logger = logger) {
                        attempts++
                        throw underlying
                    }
                }

            // 1s wait fits, the following 2s does not, so it stops after two attempts.
            assertSame(underlying, thrown)
            assertEquals(2, attempts)
        }

    @Test
    fun `a non-Payabli failure propagates untouched and unretried`() =
        runTest {
            var attempts = 0
            val thrown =
                runCatching {
                    Retry.run(policy = policy(), logger = logger) {
                        attempts++
                        throw IllegalStateException("programming error")
                    }
                }.exceptionOrNull()

            assertTrue(thrown is IllegalStateException)
            assertEquals(1, attempts)
        }

    @Test
    fun `cancellation during a backoff delay propagates as cancellation`() =
        runTest {
            val entered = CompletableDeferred<Unit>()

            val job =
                launch {
                    Retry.run(policy = policy(maxAttempts = 5), logger = logger) {
                        entered.complete(Unit)
                        throw serverError()
                    }
                }
            entered.await()
            job.cancelAndJoin()

            // Never converted into a completion, which a broad catch would have done.
            assertTrue(job.isCancelled)
        }

    @Test
    fun `a retry logs the attempt and code but never a resolved path`() =
        runTest {
            failureFrom {
                Retry.run(
                    route = "/api/v2/MoneyIn/capture/{id}",
                    policy = policy(maxAttempts = 2),
                    logger = logger,
                ) { throw serverError() }
            }

            val logged = sink.records.joinToString("\n") { it.message }
            assertTrue(logged.contains("attempt=1"))
            assertTrue(logged.contains("maxAttempts=2"))
            assertTrue(logged.contains("errorCode=SERVER_ERROR"))
            assertTrue(logged.contains("route=/api/v2/MoneyIn/capture/{id}"))
        }

    /**
     * The two budgets are tuned independently but compose in one layering, and an attempt timeout is
     * retryable. If the provider deadline ever exceeds the attempt budget, a refresh gets cancelled and
     * retried with the rejected token once per attempt. This fails the moment either constant moves.
     */
    @Test
    fun `the default attempt budget contains the default provider deadline`() {
        assertTrue(
            "attempt ${RetryPolicy.DEFAULT_ATTEMPT_TIMEOUT_MILLIS}ms must exceed the provider deadline",
            RetryPolicy.DEFAULT_ATTEMPT_TIMEOUT_MILLIS > DEFAULT_PROVIDER_TIMEOUT_MILLIS,
        )
    }
}
