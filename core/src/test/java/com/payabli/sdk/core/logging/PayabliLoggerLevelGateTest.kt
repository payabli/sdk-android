package com.payabli.sdk.core.logging

import com.payabli.sdk.core.logging.impl.DefaultPayabliLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SDK's own cutoff, which sits above the platform's per-tag level. */
class PayabliLoggerLevelGateTest {
    private val sink = RecordingLogSink()

    private fun logger(cutoff: () -> LogLevel) = DefaultPayabliLogger(LogCategory.NETWORK, sink, cutoff)

    @Test
    fun `a cutoff of NONE silences every level`() {
        val logger = logger { LogLevel.NONE }

        LogLevel.entries.forEach { level ->
            assertFalse("level $level", logger.isLoggable(level))
            logger.log(level, emptyList(), null) { "should not appear" }
        }

        assertTrue(sink.records.isEmpty())
    }

    @Test
    fun `a cutoff of NONE never invokes the message lambda`() {
        var built = 0
        logger { LogLevel.NONE }.log(LogLevel.ERROR, emptyList(), null) {
            built++
            "expensive"
        }
        assertEquals(0, built)
    }

    @Test
    fun `NONE is never emitted as a record, however low the cutoff`() {
        val logger = logger { LogLevel.DEBUG }

        assertFalse(logger.isLoggable(LogLevel.NONE))
        logger.log(LogLevel.NONE, emptyList(), null) { "should not appear" }

        assertTrue(sink.records.isEmpty())
    }

    @Test
    fun `a cutoff admits its own level and everything more severe`() {
        val logger = logger { LogLevel.WARN }

        assertFalse(logger.isLoggable(LogLevel.DEBUG))
        assertFalse(logger.isLoggable(LogLevel.INFO))
        assertTrue(logger.isLoggable(LogLevel.WARN))
        assertTrue(logger.isLoggable(LogLevel.ERROR))
        assertTrue(logger.isLoggable(LogLevel.FAULT))
    }

    @Test
    fun `the cutoff is read on every call, so lowering it affects a logger already handed out`() {
        var cutoff = LogLevel.NONE
        val logger = logger { cutoff }

        assertFalse(logger.isLoggable(LogLevel.ERROR))
        cutoff = LogLevel.ERROR
        assertTrue(logger.isLoggable(LogLevel.ERROR))
    }

    @Test
    fun `the platform gate still applies above the cutoff`() {
        // A sink that only admits ERROR stands in for a device whose per-tag level is ERROR.
        val restrictive = RecordingLogSink(loggableFrom = LogLevel.ERROR)
        val logger = DefaultPayabliLogger(LogCategory.NETWORK, restrictive) { LogLevel.DEBUG }

        assertFalse("the SDK cutoff allows it, the platform does not", logger.isLoggable(LogLevel.INFO))
        assertTrue(logger.isLoggable(LogLevel.ERROR))
    }
}
