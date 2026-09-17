package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.model.RedactedFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The producer half of the redaction contract. The consumer is in `:payin`, where a carrier wrapping a
 * failure asks whether the message has already been taken off.
 *
 * Both halves are needed because neither module can see the other's answer: this class is `internal` to
 * `:core`, so `:payin` can only recognise it through [RedactedFailure], and nothing in `:payin` fails if
 * this class stops carrying it. What breaks then is silent — a second redaction naming this SDK's own
 * wrapper in place of the type that failed.
 */
class RedactedCauseTest {
    @Test
    fun `a redacted cause announces itself as one`() {
        assertTrue(RedactedCause(IllegalStateException("could not parse 4111111111111111")) is RedactedFailure)
    }

    @Test
    fun `it keeps the type and the frames, and drops the message`() {
        val original = IllegalStateException("could not parse 4111111111111111")

        val redacted = RedactedCause(original)

        assertTrue("${redacted.message}", redacted.message?.contains("IllegalStateException") == true)
        assertFalse("${redacted.message}", redacted.message?.contains("4111111111111111") == true)
        assertTrue(redacted.stackTrace.contentEquals(original.stackTrace))
    }
}
