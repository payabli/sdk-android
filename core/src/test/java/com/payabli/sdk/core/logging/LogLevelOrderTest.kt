package com.payabli.sdk.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A canary, not a behaviour test. [LogLevel]'s declaration order is load-bearing twice over: the
 * generated `Comparable` is the severity comparison, and `NONE` sorting above every severity is what
 * makes it mean "silent". Either can be broken by an innocent-looking insertion, and nothing else
 * would fail in a way that names the cause.
 */
class LogLevelOrderTest {
    @Test
    fun `the ladder is declared least severe first, with NONE last`() {
        assertEquals(
            "reordering LogLevel changes the meaning of every severity comparison",
            listOf(
                LogLevel.DEBUG,
                LogLevel.INFO,
                LogLevel.WARN,
                LogLevel.ERROR,
                LogLevel.FAULT,
                LogLevel.NONE,
            ),
            LogLevel.entries.toList(),
        )
    }

    @Test
    fun `no record level passes a cutoff of NONE`() {
        assertFalse("a value declared after NONE would still be emitted when silenced", LogLevel.FAULT >= LogLevel.NONE)
        assertTrue(LogLevel.entries.none { it.isRecordLevel && it >= LogLevel.NONE })
    }

    @Test
    fun `NONE is the only level that is not a record level`() {
        assertEquals(listOf(LogLevel.NONE), LogLevel.entries.filterNot { it.isRecordLevel })
    }
}
