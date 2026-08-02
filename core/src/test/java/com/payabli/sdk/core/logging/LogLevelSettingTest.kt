package com.payabli.sdk.core.logging

import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.logging.impl.LogLevelSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Precedence between the explicit cutoff and the automatic one. A fresh instance per test, which is
 * why this class exists separately from the static locator: no case can leak into the next.
 */
class LogLevelSettingTest {
    private val setting = LogLevelSetting()

    @Test
    fun `silent until something says otherwise`() {
        assertEquals(LogLevel.NONE, setting.effective())
    }

    @Test
    fun `a debuggable host stops the SDK filtering by severity`() {
        setting.setHostDebuggable(true)

        assertEquals(LogLevel.DEBUG, setting.effective())
    }

    @Test
    fun `a host that is not debuggable stays silent`() {
        setting.setHostDebuggable(false)

        assertEquals(LogLevel.NONE, setting.effective())
    }

    @Test
    fun `an explicit cutoff set first still beats a debuggable host`() {
        setting.setExplicit(LogLevel.WARN)
        setting.setHostDebuggable(true)

        assertEquals(LogLevel.WARN, setting.effective())
    }

    @Test
    fun `an explicit cutoff set afterwards beats a debuggable host`() {
        setting.setHostDebuggable(true)
        setting.setExplicit(LogLevel.WARN)

        assertEquals(LogLevel.WARN, setting.effective())
    }

    @Test
    fun `deliberate silence set first is not overridden by a debuggable host`() {
        setting.setExplicit(LogLevel.NONE)
        setting.setHostDebuggable(true)

        assertEquals(LogLevel.NONE, setting.effective())
    }

    @Test
    fun `deliberate silence set afterwards silences a debuggable host`() {
        setting.setHostDebuggable(true)
        setting.setExplicit(LogLevel.NONE)

        assertEquals(LogLevel.NONE, setting.effective())
    }

    @Test
    fun `clearing the explicit cutoff falls back to the automatic one`() {
        setting.setHostDebuggable(true)
        setting.setExplicit(LogLevel.NONE)

        setting.clearExplicit()

        assertEquals(LogLevel.DEBUG, setting.effective())
    }

    @Test
    fun `clearing with no automatic value leaves the SDK silent`() {
        setting.setExplicit(LogLevel.DEBUG)

        setting.clearExplicit()

        assertEquals(LogLevel.NONE, setting.effective())
    }

    @Test
    fun `re-reading the host flag does not clobber an explicit cutoff`() {
        setting.setExplicit(LogLevel.ERROR)

        setting.setHostDebuggable(true)
        setting.setHostDebuggable(true)

        assertEquals(LogLevel.ERROR, setting.effective())
    }

    @Test
    fun `a logger already handed out sees a later change`() {
        val sink = RecordingLogSink()
        val logger: SdkLogger = DefaultSdkLogger(LogCategory.NETWORK, sink, setting::effective)

        assertFalse("silent by default", logger.isLoggable(LogLevel.ERROR))

        setting.setHostDebuggable(true)

        assertTrue(logger.isLoggable(LogLevel.ERROR))
    }
}
