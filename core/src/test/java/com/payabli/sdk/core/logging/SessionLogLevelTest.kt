package com.payabli.sdk.core.logging

import com.payabli.sdk.core.PayabliSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The app-facing log control, exercised the way an integrator reaches it rather than through the locator.
 *
 * The delegation hop is the point: `PayabliSession.setLogLevel` adds a layer between the caller and the
 * cutoff, and a layer that quietly dropped the call would leave every precedence test in
 * `LogLevelSettingTest` passing while the public surface did nothing.
 *
 * It sits on the session's companion rather than on a logging object of its own, on the type that owns
 * `initialize`. That is what lets an explicit level be set before `initialize` derives the automatic one,
 * which is the only way "explicit wins in either order" can hold in both directions.
 *
 * Process-wide state, so it is restored afterwards, and the restore is asserted: a cutoff left lowered here
 * reaches the real sink in a later class and fails it with "not mocked" from inside an unrelated refresh.
 */
class SessionLogLevelTest {
    @After
    fun restoreProcessWideState() {
        LoggerRegistry.clearLogLevel()
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
    fun `setLogLevel reaches the cutoff`() {
        PayabliSession.setLogLevel(LogLevel.WARN)

        assertEquals(LogLevel.WARN, LoggerRegistry.effectiveLogLevel())
    }

    @Test
    fun `an app silencing the SDK is not overridden by its own debug build`() {
        PayabliSession.setLogLevel(LogLevel.NONE)
        LoggerRegistry.setHostDebuggable(true)

        assertEquals(LogLevel.NONE, LoggerRegistry.effectiveLogLevel())
        assertFalse(LoggerRegistry.of(LogCategory.CORE).isLoggable(LogLevel.FAULT))
    }

    @Test
    fun `an app silencing the SDK after the debug build is detected still wins`() {
        LoggerRegistry.setHostDebuggable(true)
        PayabliSession.setLogLevel(LogLevel.NONE)

        assertEquals(LogLevel.NONE, LoggerRegistry.effectiveLogLevel())
    }

    @Test
    fun `an app asking for records in a release build gets them`() {
        // Not debuggable, which is what a release build reads as, so only the explicit setting can open it.
        LoggerRegistry.setHostDebuggable(false)

        PayabliSession.setLogLevel(LogLevel.INFO)

        assertEquals(LogLevel.INFO, LoggerRegistry.effectiveLogLevel())
    }

    @Test
    fun `a level set before initialize survives it`() {
        // The reason the setter is type-level at all. An instance-owned one could not be called here, so the
        // automatic value derived inside initialize would always be the later write and would always win.
        PayabliSession.setLogLevel(LogLevel.NONE)
        LoggerRegistry.setHostDebuggable(true)

        assertEquals(LogLevel.NONE, LoggerRegistry.effectiveLogLevel())
    }
}
