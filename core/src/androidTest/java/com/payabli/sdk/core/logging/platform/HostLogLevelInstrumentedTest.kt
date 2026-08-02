package com.payabli.sdk.core.logging.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.core.logging.PayabliLoggers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The debuggable-flag read and the whole automatic path, end to end, which is only true on a device:
 * a real `ApplicationInfo`, the real `android.util.Log.isLoggable`, and a real debuggable application.
 *
 * The test APK is built from the debug build type, so the application under test is expected to be
 * debuggable. That is the assertion rather than an assumption; if AGP ever marks it otherwise, the
 * first test says so instead of quietly passing.
 */
@RunWith(AndroidJUnit4::class)
class HostLogLevelInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun restoreProcessWideState() {
        PayabliLoggers.clearLogLevel()
        PayabliLoggers.setHostDebuggable(false)
    }

    @Test
    fun aDebuggableHostStopsTheSdkFilteringBySeverity() {
        context.applyHostLogLevel()

        assertEquals(LogLevel.DEBUG, PayabliLoggers.effectiveLogLevel())
    }

    @Test
    fun aDebuggableHostEmitsThroughTheRealPlatformGate() {
        context.applyHostLogLevel()

        // INFO is the platform's documented default per-tag level, so it needs no setprop. DEBUG
        // deliberately is not asserted: it depends on a device property this SDK does not own.
        val logger = PayabliLoggers.of(LogCategory.CORE)
        assertTrue(logger.isLoggable(LogLevel.INFO))
        assertTrue(logger.isLoggable(LogLevel.ERROR))
        logger.log(LogLevel.INFO, emptyList(), null) { "host log level applied" }
    }

    @Test
    fun deliberateSilenceSurvivesADebuggableHost() {
        PayabliLoggers.setLogLevel(LogLevel.NONE)

        context.applyHostLogLevel()

        assertEquals(LogLevel.NONE, PayabliLoggers.effectiveLogLevel())
        assertFalse(PayabliLoggers.of(LogCategory.CORE).isLoggable(LogLevel.FAULT))
    }

    @Test
    fun silentUntilTheHostFlagIsRead() {
        assertEquals(LogLevel.NONE, PayabliLoggers.effectiveLogLevel())
        assertFalse(PayabliLoggers.of(LogCategory.CORE).isLoggable(LogLevel.FAULT))
    }
}
