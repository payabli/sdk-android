package com.payabli.sdk.core.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The app-facing control, exercised the way an integrator reaches it rather than through the locator.
 *
 * The delegation hop is the point: `PayabliLogging` adds a layer between the caller and the cutoff, and a
 * layer that quietly dropped the call would leave every precedence test in `LogLevelSettingTest` passing
 * while the public surface did nothing.
 *
 * Process-wide state, so it is restored afterwards, and the restore is asserted: a cutoff left lowered here
 * reaches the real sink in a later class and fails it with "not mocked" from inside an unrelated refresh.
 */
class PayabliLoggingTest {
    @After
    fun restoreProcessWideState() {
        PayabliLogging.resetLevel()
        LoggerRegistry.setHostDebuggable(false)

        assertEquals(
            "left the SDK verbose for every later test class in this JVM",
            LogLevel.NONE,
            LoggerRegistry.effectiveLogLevel(),
        )
    }

    @Test
    fun `silent until an app says otherwise`() {
        assertEquals(LogLevel.NONE, LoggerRegistry.effectiveLogLevel())
    }

    @Test
    fun `setLevel reaches the cutoff`() {
        PayabliLogging.setLevel(LogLevel.WARN)

        assertEquals(LogLevel.WARN, LoggerRegistry.effectiveLogLevel())
    }

    @Test
    fun `resetLevel returns to the host-debuggable default`() {
        LoggerRegistry.setHostDebuggable(true)
        PayabliLogging.setLevel(LogLevel.ERROR)

        PayabliLogging.resetLevel()

        assertEquals(LogLevel.DEBUG, LoggerRegistry.effectiveLogLevel())
    }

    @Test
    fun `an app silencing the SDK is not overridden by its own debug build`() {
        PayabliLogging.setLevel(LogLevel.NONE)
        LoggerRegistry.setHostDebuggable(true)

        assertEquals(LogLevel.NONE, LoggerRegistry.effectiveLogLevel())
        assertFalse(LoggerRegistry.of(LogCategory.CORE).isLoggable(LogLevel.FAULT))
    }

    @Test
    fun `an app silencing the SDK after the debug build is detected still wins`() {
        LoggerRegistry.setHostDebuggable(true)
        PayabliLogging.setLevel(LogLevel.NONE)

        assertEquals(LogLevel.NONE, LoggerRegistry.effectiveLogLevel())
    }

    @Test
    fun `an app asking for records in a release build gets them`() {
        // Not debuggable, which is what a release build reads as, so only the explicit setting can open it.
        LoggerRegistry.setHostDebuggable(false)

        PayabliLogging.setLevel(LogLevel.INFO)

        assertEquals(LogLevel.INFO, LoggerRegistry.effectiveLogLevel())
    }
}
