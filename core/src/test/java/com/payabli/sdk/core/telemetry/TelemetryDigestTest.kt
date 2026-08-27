package com.payabli.sdk.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The derivation, pinned.
 *
 * Every value here was computed outside this codebase, so the test states what the answer is rather than
 * what this implementation happens to produce. That is the whole point of it: the sibling platform has to
 * reach the same value for the same input, and a hash that only agrees with itself aggregates nothing.
 */
class TelemetryDigestTest {
    @Test
    fun theDigestIsTheFirstHalfOfASha256InLowercaseHex() {
        assertEquals("2cdd1350178a0114c62f4a2eb59400ce", TelemetryDigest.of("an-entry-point"))
        assertEquals("837c57db307b1a3804b08e751641057f", TelemetryDigest.of("com.payabli.example.app"))
    }

    @Test
    fun theDigestIs32Characters() {
        assertEquals(32, TelemetryDigest.of("an-entry-point").length)
    }

    /** Nothing is folded, so the bytes hashed are the ones the request is authorized with. */
    @Test
    fun caseAndSurroundingSpaceAreNotNormalisedAway() {
        assertEquals("ca978112ca1bbdcafac231b39a23dc4d", TelemetryDigest.of("a"))
        assertEquals("559aead08264d5795d3909718cdd05ab", TelemetryDigest.of("A"))
        assertEquals("7de598b3ff8a99638e1f9bf49260baa0", TelemetryDigest.of(" a"))
    }

    /** A blank stays blank, which the wire omits rather than sending as an empty field. */
    @Test
    fun nothingInIsNothingOut() {
        assertEquals("", TelemetryDigest.of(""))
        assertEquals("", TelemetryDigest.of("   "))
    }

    @Test
    fun twoEntryPointsDoNotShareADigest() {
        assertNotEquals(TelemetryDigest.of("one-entry-point"), TelemetryDigest.of("another-entry-point"))
    }
}
