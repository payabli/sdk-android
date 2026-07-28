package com.payabli.sdk.core.logging

import com.payabli.sdk.core.logging.impl.DefaultPayabliLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message lambda exists so a disabled level costs nothing and, more importantly, so a sensitive
 * interpolation is never built at all. Both halves are asserted.
 */
class PayabliLoggerLazinessTest {
    private val sink = RecordingLogSink(loggableFrom = LogLevel.INFO)
    private val logger: PayabliLogger = DefaultPayabliLogger(LogCategory.CORE, sink)

    @Test
    fun disabledLevelNeverBuildsTheMessage() {
        var invocations = 0

        logger.debug {
            invocations++
            "must never be composed"
        }

        assertEquals(0, invocations)
        assertTrue(sink.records.isEmpty())
    }

    @Test
    fun enabledLevelBuildsTheMessageExactlyOnce() {
        var invocations = 0

        logger.info {
            invocations++
            "composed"
        }

        assertEquals(1, invocations)
        assertEquals("composed", sink.single().message)
    }

    @Test
    fun isLoggableTracksTheSink() {
        assertFalse(logger.isLoggable(LogLevel.DEBUG))
        assertTrue(logger.isLoggable(LogLevel.INFO))
        assertTrue(logger.isLoggable(LogLevel.FAULT))
    }

    @Test
    fun everyLevelReachesTheSinkAtItsOwnSeverity() {
        val records = RecordingLogSink()
        val subject: PayabliLogger = DefaultPayabliLogger(LogCategory.AUTH, records)

        subject.debug { "d" }
        subject.info { "i" }
        subject.warn { "w" }
        subject.error { "e" }
        subject.fault { "f" }

        assertEquals(LogLevel.entries.toList(), records.records.map { it.level })
        assertTrue(records.records.all { it.tag == "PayabliAuth" })
    }
}
