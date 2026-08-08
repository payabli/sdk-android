package com.payabli.sdk.core.logging

import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.logging.impl.LogRecordFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Composition, the logd payload ceiling, and the scrub-before-truncate ordering. */
class LogRecordFormatterTest {
    private val tag = LogCategory.NETWORK.tag

    @Test
    fun shortLineIsNotTouched() {
        assertEquals(
            "capture rejected route=/capture",
            LogRecordFormatter.format("capture rejected", "route=/capture", null, tag),
        )
    }

    @Test
    fun emptyFieldsAndThrowableProduceJustTheMessage() {
        assertEquals("ready", LogRecordFormatter.format("ready", "", null, tag))
    }

    @Test
    fun throwableGoesOnItsOwnLine() {
        assertEquals("failed\njava.io.IOException", LogRecordFormatter.format("failed", "", "java.io.IOException", tag))
    }

    @Test
    fun longLineIsTruncatedWithinThePayloadCeiling() {
        val line = LogRecordFormatter.format("z".repeat(10_000), "", null, tag)

        assertTrue(line.endsWith(LogRecordFormatter.TRUNCATION_MARKER))
        assertTrue(line.toByteArray().size <= LogRecordFormatter.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun multiByteLineIsCutOnACodePointBoundary() {
        val line = LogRecordFormatter.format("é".repeat(5_000), "", null, tag)

        assertTrue(line.toByteArray().size <= LogRecordFormatter.MAX_PAYLOAD_BYTES)
        // Written as an escape. Sonar reads a literal U+FFFD as a file that failed to decode,
        // because that is the character a decoder emits for undecodable input, and reports the
        // whole analysis as having an encoding problem.
        assertFalse("cut mid code point", line.contains('\uFFFD'))
    }

    @Test
    fun scrubbingHappensBeforeTruncation() {
        val sink = RecordingLogSink()
        val logger: SdkLogger = DefaultSdkLogger(LogCategory.NETWORK, sink)
        val cut = LogRecordFormatter.MAX_PAYLOAD_BYTES - tag.length - LogRecordFormatter.TRUNCATION_MARKER.length
        // Filler length is picked so a truncate-first implementation would keep ten of the sixteen
        // digits, below the twelve-digit floor, and a later scrub would no longer match them.
        val filler = "x".repeat(cut - "[REDACTED]".length)

        logger.info { filler + LogFixtures.DIGITS_16 + "y".repeat(2_000) }

        val message = sink.single().message
        assertFalse("digits survived the cut", message.any { it.isDigit() })
        assertTrue(message.contains("[REDACTED]"))
        assertTrue(message.endsWith(LogRecordFormatter.TRUNCATION_MARKER))
    }
}
