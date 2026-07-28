package com.payabli.sdk.core.logging

import com.payabli.sdk.core.logging.impl.SensitiveDataScrubber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The free-text net under the message position, which no allowlist can reach. */
class SensitiveDataScrubberTest {
    @Test
    fun digitRunBelowTheFloorSurvives() {
        assertEquals(LogFixtures.DIGITS_11, SensitiveDataScrubber.scrub(LogFixtures.DIGITS_11))
    }

    @Test
    fun digitRunAtTheFloorIsRedacted() {
        // ISO/IEC 7812 permits PANs shorter than 13 digits, so the floor is 12.
        assertEquals("[REDACTED]", SensitiveDataScrubber.scrub(LogFixtures.DIGITS_12))
    }

    @Test
    fun separatedRunsAreRedacted() {
        assertEquals("[REDACTED]", SensitiveDataScrubber.scrub(LogFixtures.DIGITS_16))
        assertEquals("[REDACTED]", SensitiveDataScrubber.scrub(LogFixtures.DIGITS_16_SPACED))
        assertEquals("[REDACTED]", SensitiveDataScrubber.scrub(LogFixtures.DIGITS_16_DASHED))
    }

    @Test
    fun overLongRunIsFullyConsumed() {
        // An over-long run must be consumed whole, not partially replaced.
        val scrubbed = SensitiveDataScrubber.scrub(LogFixtures.DIGITS_25)
        assertEquals("[REDACTED]", scrubbed)
        assertFalse(scrubbed.any { it.isDigit() })
    }

    @Test
    fun embeddedRunIsRedactedWithoutEatingItsSurroundings() {
        assertEquals(
            "pan=[REDACTED] end",
            SensitiveDataScrubber.scrub("pan=${LogFixtures.DIGITS_16} end"),
        )
    }

    @Test
    fun epochMillisIsRedactedDeliberately() {
        // Accepted false positive, pinned so it is intentional rather than surprising: log ISO-8601
        // (whose dashes break the run at eight digits) or a duration, never raw epoch millis.
        assertEquals("[REDACTED]", SensitiveDataScrubber.scrub(LogFixtures.EPOCH_MILLIS_13))
    }

    @Test
    fun typeTaggedTokenIsRedacted() {
        assertEquals("[REDACTED]", SensitiveDataScrubber.scrub(LogFixtures.REFRESH_TOKEN))
    }

    @Test
    fun compactJwsIsRedacted() {
        assertEquals("[REDACTED]", SensitiveDataScrubber.scrub(LogFixtures.ACCESS_JWT))
    }

    @Test
    fun bearerHeaderValueIsRedactedButTheSchemeSurvives() {
        assertEquals(
            "Authorization: Bearer [REDACTED]",
            SensitiveDataScrubber.scrub("Authorization: Bearer abcdefgh.ijklmnop"),
        )
    }

    @Test
    fun pemBlockIsRedacted() {
        assertEquals("[REDACTED]", SensitiveDataScrubber.scrub(LogFixtures.PEM_BLOCK))
    }

    @Test
    fun emailIsRedacted() {
        val scrubbed = SensitiveDataScrubber.scrub("contact ${LogFixtures.EMAIL} now")
        assertEquals("contact [REDACTED] now", scrubbed)
    }

    @Test
    fun sidSurvivesEveryRule() {
        // There is deliberately no generic high-entropy rule: it would redact `sid`, which is loggable by
        // design and is the primary correlation handle.
        assertEquals(LogFixtures.SID, SensitiveDataScrubber.scrub(LogFixtures.SID))
        assertEquals(LogFixtures.ROUTE, SensitiveDataScrubber.scrub(LogFixtures.ROUTE))
    }

    @Test(timeout = 5_000)
    fun pathologicalInputCompletes() {
        // A ReDoS canary for the anchored lookaround pattern and the lazy PEM pattern.
        val hostile = "9 ".repeat(50_000)
        val scrubbed = SensitiveDataScrubber.scrub(hostile)
        assertTrue(scrubbed.contains("[REDACTED]"))
        assertFalse(scrubbed.any { it.isDigit() })
    }
}
