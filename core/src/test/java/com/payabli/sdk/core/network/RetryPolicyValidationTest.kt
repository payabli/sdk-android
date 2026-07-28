package com.payabli.sdk.core.network

import org.junit.Assert.assertThrows
import org.junit.Test

/** Every timing input is validated, so an invalid policy fails loudly rather than masking a network error. */
class RetryPolicyValidationTest {
    private fun rejects(block: () -> RetryPolicy) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

    @Test
    fun `maxAttempts below one is rejected`() = rejects { RetryPolicy(maxAttempts = 0) }

    @Test
    fun `a negative base delay is rejected`() = rejects { RetryPolicy(baseDelayMillis = -1) }

    @Test
    fun `a max delay below the base delay is rejected`() =
        rejects { RetryPolicy(baseDelayMillis = 2_000, maxDelayMillis = 1_000) }

    @Test
    fun `a multiplier below one is rejected`() = rejects { RetryPolicy(multiplier = 0.5) }

    @Test
    fun `a non-finite multiplier is rejected`() = rejects { RetryPolicy(multiplier = Double.POSITIVE_INFINITY) }

    @Test
    fun `a negative jitter bound is rejected`() = rejects { RetryPolicy(maxJitterMillis = -1) }

    @Test
    fun `a jitter bound that would overflow the range is rejected`() =
        rejects { RetryPolicy(maxJitterMillis = Long.MAX_VALUE) }

    @Test
    fun `a non-positive attempt timeout is rejected`() = rejects { RetryPolicy(attemptTimeoutMillis = 0) }

    @Test
    fun `a non-positive total timeout is rejected`() = rejects { RetryPolicy(totalTimeoutMillis = 0) }

    @Test
    fun `a negative retry-after ceiling is rejected`() = rejects { RetryPolicy(maxRetryAfterMillis = -1) }

    @Test
    fun `the defaults are valid`() {
        RetryPolicy()
    }
}
