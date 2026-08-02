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
 * passing a logger get `PayabliLoggers.of(...)`, so a cutoff left lowered here reaches the real sink
 * in a later class and fails it with "Method isLoggable in android.util.Log not mocked" from inside
 * a refresh. Measured, not hypothetical: sabotaging the reset failed six auth and transport tests and
 * none in this package. Hence the assertion below, so a leak fails in the class that caused it.
 */
class PayabliLoggersTest {
    @After
    fun restoreProcessWideState() {
        PayabliLoggers.clearLogLevel()
        PayabliLoggers.setHostDebuggable(false)

        assertEquals(
            "left the SDK verbose for every later test class in this JVM",
            LogLevel.NONE,
            PayabliLoggers.effectiveLogLevel(),
        )
    }

    @Test
    fun `an explicit cutoff reaches the loggers`() {
        PayabliLoggers.setLogLevel(LogLevel.WARN)

        assertEquals(LogLevel.WARN, PayabliLoggers.effectiveLogLevel())
    }

    @Test
    fun `an explicit NONE silences`() {
        PayabliLoggers.setLogLevel(LogLevel.NONE)

        assertEquals(LogLevel.NONE, PayabliLoggers.effectiveLogLevel())
        assertFalse(PayabliLoggers.of(LogCategory.CORE).isLoggable(LogLevel.FAULT))
    }

    @Test
    fun `a debuggable host reaches the loggers`() {
        PayabliLoggers.setHostDebuggable(true)

        assertEquals(LogLevel.DEBUG, PayabliLoggers.effectiveLogLevel())
    }

    @Test
    fun `an explicit cutoff still wins through the locator`() {
        PayabliLoggers.setHostDebuggable(true)
        PayabliLoggers.setLogLevel(LogLevel.NONE)

        assertEquals(LogLevel.NONE, PayabliLoggers.effectiveLogLevel())
    }

    @Test
    fun `clearing returns to the automatic value`() {
        PayabliLoggers.setHostDebuggable(true)
        PayabliLoggers.setLogLevel(LogLevel.ERROR)

        PayabliLoggers.clearLogLevel()

        assertEquals(LogLevel.DEBUG, PayabliLoggers.effectiveLogLevel())
    }

    @Test
    fun `every category shares one cutoff`() {
        PayabliLoggers.setLogLevel(LogLevel.NONE)

        LogCategory.entries.forEach { category ->
            assertFalse("category $category", PayabliLoggers.of(category).isLoggable(LogLevel.FAULT))
        }
    }
}
