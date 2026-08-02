@file:OptIn(ExperimentalCoroutinesApi::class)

package com.payabli.sdk.core.network

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.model.PayabliDeclineException
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.model.PayabliRateLimitException
import com.payabli.sdk.core.model.PayabliServerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timing is asserted on `TestScope.currentTime`, which is exact because `runTest` runs `delay` in virtual
 * time. That only holds because [Retry] never switches dispatcher.
 */
class RetryTest {
    private val sink = RecordingLogSink()
    private val logger = DefaultSdkLogger(LogCategory.NETWORK, sink)

    /** No jitter, so every schedule below is an exact number rather than a range. */
    private fun policy(
        maxAttempts: Int = 3,
        totalTimeoutMillis: Long? = null,
        maxRetryAfterMillis: Long = RetryPolicy.DEFAULT_MAX_RETRY_AFTER_MILLIS,
    ) = RetryPolicy(
        maxAttempts = maxAttempts,
        totalTimeoutMillis = totalTimeoutMillis,
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

    // ---- the total budget bounds the attempt, not just the decision to sleep again ----------------

    /**
     * The regression this pins: with the attempt clamp gone, nothing bounded a running attempt, so a policy
     * with a 1-second total budget returned successfully from a 5-second attempt.
     *
     * `attempts` is the load-bearing assertion. A budget expiry that was retryable would show up here as 3,
     * and that is exactly the storm the removed per-attempt timeout used to cause.
     *
     * The scheduler's time source is passed for the same reason as the backoff test below, and here it is
     * what makes the `currentTime` assertion stable rather than merely meaningful. `TimeSource.Monotonic`
     * reads real time while `runTest` advances the timeout virtually, so every real millisecond spent
     * between `markNow()` and the timeout comes straight off the budget. Measured with an injected lag:
     * 1ms lands `currentTime` on 999 and 25ms lands it on 975, against an assertion of exactly 1,000. It
     * passed on a quiet machine and failed on a loaded CI runner, which is precisely what it did.
     */
    @Test
    fun `the total budget cuts off an in-flight attempt and does not retry`() =
        runTest {
            var attempts = 0

            val thrown =
                failureFrom {
                    Retry.run(
                        policy = policy(maxAttempts = 3, totalTimeoutMillis = 1_000),
                        logger = logger,
                        timeSource = testScheduler.timeSource,
                    ) {
                        attempts++
                        delay(5_000)
                        "never returned"
                    }
                }

            assertEquals(PayabliErrorCode.NETWORK_ERROR, thrown.code)
            // The reason, not just the code: NETWORK_ERROR is also what a refused socket produces, so without
            // this the test would pass against an unrelated network failure.
            assertEquals("Operation exceeded its total timeout", thrown.reason)
            assertEquals("the expiry was retried", 1, attempts)
            assertEquals("the budget was not what ended it", 1_000, currentTime)
        }

    /**
     * Backoff spends the same budget the attempts do, which is what "one deadline for the whole operation"
     * means and what could not previously be tested.
     *
     * The scheduler's own time source is passed in because `TimeSource.Monotonic` reads real time while
     * `runTest` advances `delay` virtually: without it a virtual backoff consumed none of the budget, so this
     * assertion would hold no matter what the implementation did.
     *
     * 1,200ms of budget against a 1,000ms first backoff. Attempt one fails immediately, the wait leaves 200ms,
     * and attempt two is then cut off well before the 5,000ms it wants rather than being handed a fresh 1,200.
     */
    @Test
    fun `a backoff wait consumes the budget the next attempt gets`() =
        runTest {
            var attempts = 0

            val thrown =
                failureFrom {
                    Retry.run(
                        policy = policy(maxAttempts = 3, totalTimeoutMillis = 1_200),
                        logger = logger,
                        timeSource = testScheduler.timeSource,
                    ) {
                        attempts++
                        if (attempts == 1) throw serverError()
                        delay(5_000)
                        "never returned"
                    }
                }

            assertEquals(PayabliErrorCode.NETWORK_ERROR, thrown.code)
            assertEquals("Operation exceeded its total timeout", thrown.reason)
            assertEquals("the second attempt was allowed to start and be cut off", 2, attempts)
            // 1,000ms of backoff plus the 200ms remainder. A budget that reset per attempt would reach 6,000.
            assertEquals(1_200, currentTime)
        }

    /** Null is not "some large deadline": this layer must install none at all. */
    @Test
    fun `an unbounded policy imposes no deadline of its own`() =
        runTest {
            var attempts = 0

            val result =
                Retry.run(policy = policy(totalTimeoutMillis = null), logger = logger) {
                    attempts++
                    delay(300_000)
                    "ok"
                }

            assertEquals("ok", result)
            assertEquals(1, attempts)
            assertEquals(300_000, currentTime)
        }

    /**
     * Cancellation raised *inside* the operation escapes rather than being converted.
     *
     * It does **not** reach the `outcome == null` branch, proven with a sentinel there: cancelling the
     * innermost job makes `delay` throw a plain `CancellationException`, and `withTimeoutOrNull` returns null
     * only for its own `TimeoutCancellationException`, so this propagates before `outcome` is assigned. What
     * it guards is the shape: swapping in `withTimeout` plus a broad catch would convert this to a timeout.
     */
    @Test
    fun `cancellation inside the operation escapes instead of becoming a total-timeout error`() =
        runTest {
            val thrown =
                runCatching {
                    Retry.run(policy = policy(maxAttempts = 1, totalTimeoutMillis = 1), logger = logger) {
                        currentCoroutineContext().cancel()
                        delay(Long.MAX_VALUE)
                    }
                }.exceptionOrNull()

            assertTrue("got $thrown", thrown is CancellationException)
            assertTrue("got $thrown", thrown !is PayabliException)
        }

    /**
     * A budget larger than the nanosecond range behaves like a long one, not like an expired one.
     *
     * Passes against both the current `Duration` arithmetic and the nanosecond arithmetic it replaced, and
     * that is worth recording rather than hiding: the overflow a review raised is real but cancels, because
     * two's complement subtraction is exact modulo 2^64. Kept as a cheap guard on the boundary, not as
     * evidence of a fix.
     */
    @Test
    fun `a budget too large to hold in nanoseconds does not expire immediately`() =
        runTest {
            var attempts = 0

            val result =
                Retry.run(policy = policy(totalTimeoutMillis = Long.MAX_VALUE), logger = logger) {
                    attempts++
                    "ok"
                }

            assertEquals("ok", result)
            assertEquals(1, attempts)
        }

    /** Cancelled from outside while parked on a `Deferred`, so no timer is involved in the delivery. */
    @Test
    fun `caller cancellation during a total-budgeted attempt remains cancellation`() =
        runTest {
            val entered = CompletableDeferred<Unit>()

            val job =
                launch {
                    Retry.run(policy = policy(maxAttempts = 3, totalTimeoutMillis = 10_000), logger = logger) {
                        entered.complete(Unit)
                        CompletableDeferred<String>().await()
                    }
                }

            entered.await()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
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

    /**
     * The deadline must not swallow the caller's own cancellation.
     *
     * Converting a timeout by catching `TimeoutCancellationException` is what would break this, which is why
     * the implementation converts a null result from `withTimeoutOrNull` instead and catches nothing.
     *
     * Cancelled from outside while parked on a timer, and asserts the exception type rather than the job's
     * state, so the failure message names what escaped.
     */
    @Test
    fun `cancelling the caller stays cancellation rather than becoming a budget failure`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var failure: Throwable? = null

            val job =
                launch {
                    try {
                        Retry.run(policy = policy(totalTimeoutMillis = 60_000), logger = logger) {
                            started.complete(Unit)
                            delay(30_000)
                            "never returned"
                        }
                    } catch (t: Throwable) {
                        failure = t
                        throw t
                    }
                }

            started.await()
            job.cancelAndJoin()

            assertTrue("expected cancellation, got $failure", failure is CancellationException)
        }
}
