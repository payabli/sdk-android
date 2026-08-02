package com.payabli.sdk.core.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * That the locator is wired to its cutoff, which `LogLevelSettingTest` covers on its own. Kept
 * separate because this touches process-wide state, so it restores both slots afterwards.
 *
 * It cannot assert a *positive* `isLoggable`: the production sink reaches `android.util.Log`, which
 * throws on the JVM, and `unitTests.returnDefaultValues` stays unset deliberately. The real sink is
 * exercised on a device by `HostLogLevelInstrumentedTest`.
 *
 * **The restore is not housekeeping.** Classes that construct `PayabliAuth` or a transport without
 * passing a logger get `LoggerRegistry.of(...)`, so a cutoff left lowered here reaches the real sink
 * in a later class and fails it with "Method isLoggable in android.util.Log not mocked" from inside
 * a refresh. Measured, not hypothetical: sabotaging the reset failed six auth and transport tests and
 * none in this package. Hence the assertion below, so a leak fails in the class that caused it.
 */
class LoggerRegistryTest {
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
    fun `an explicit cutoff reaches the loggers`() {
        LoggerRegistry.setLogLevel(LogLevel.WARN)

        assertEquals(LogLevel.WARN, LoggerRegistry.effectiveLogLevel())
    }

    @Test
    fun `an explicit NONE silences`() {
        LoggerRegistry.setLogLevel(LogLevel.NONE)

        assertEquals(LogLevel.NONE, LoggerRegistry.effectiveLogLevel())
        assertFalse(LoggerRegistry.of(LogCategory.CORE).isLoggable(LogLevel.FAULT))
    }

    @Test
    fun `a debuggable host reaches the loggers`() {
        LoggerRegistry.setHostDebuggable(true)

        assertEquals(LogLevel.DEBUG, LoggerRegistry.effectiveLogLevel())
    }

    @Test
    fun `an explicit cutoff still wins through the locator`() {
        LoggerRegistry.setHostDebuggable(true)
        LoggerRegistry.setLogLevel(LogLevel.NONE)

        assertEquals(LogLevel.NONE, LoggerRegistry.effectiveLogLevel())
    }

    @Test
    fun `clearing returns to the automatic value`() {
        LoggerRegistry.setHostDebuggable(true)
        LoggerRegistry.setLogLevel(LogLevel.ERROR)

        LoggerRegistry.clearLogLevel()

        assertEquals(LogLevel.DEBUG, LoggerRegistry.effectiveLogLevel())
    }

    @Test
    fun `every category shares one cutoff`() {
        LoggerRegistry.setLogLevel(LogLevel.NONE)

        LogCategory.entries.forEach { category ->
            assertFalse("category $category", LoggerRegistry.of(category).isLoggable(LogLevel.FAULT))
        }
    }
}
