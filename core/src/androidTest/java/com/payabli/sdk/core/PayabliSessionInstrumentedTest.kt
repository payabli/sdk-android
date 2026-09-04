package com.payabli.sdk.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.core.logging.LoggerRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That `initialize` derives the automatic log level, which is the half of the feature no JVM test can reach.
 *
 * `HostLogLevelInstrumentedTest` already covers the derivation itself: a real `ApplicationInfo`, the real
 * per-tag gate, and explicit-beats-automatic in both orders. What it does not cover is the caller, so these
 * assert the wiring alone and do not restate what that class proves.
 *
 * The test APK is built debug, so the application under test is debuggable. That is the premise the first
 * test rests on and the reason it can assert `DEBUG` rather than merely "changed".
 */
@RunWith(AndroidJUnit4::class)
class PayabliSessionInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun config() =
        PayabliConfig(
            entryPoint = "entry",
            environment = PayabliEnvironment.SANDBOX,
        )

    @After
    fun restoreProcessWideState() {
        runBlocking { PayabliSession.reset() }
        LoggerRegistry.clearLogLevel()
        LoggerRegistry.setHostDebuggable(false)

        assertEquals(
            "left the SDK verbose for every later test class in this process",
            LogLevel.NONE,
            LoggerRegistry.effectiveLogLevel(),
        )
        assertEquals(
            "left the SDK's published state set for every later test class in this process",
            SdkState.Uninitialized,
            PayabliSession.state.value,
        )
    }

    @Test
    fun initializeDerivesTheAutomaticLevelFromADebuggableHost() {
        assertEquals("nothing should have lowered the cutoff yet", LogLevel.NONE, LoggerRegistry.effectiveLogLevel())

        runBlocking { PayabliSession.initialize(config(), HostBindings(context)).getOrThrow() }

        // The one line this ticket owed: without it the automatic slot is never written and an integrator's
        // debug build stays silent no matter how the SDK is configured.
        assertEquals(LogLevel.DEBUG, LoggerRegistry.effectiveLogLevel())
    }

    @Test
    fun aLevelChosenBeforeInitializeSurvivesIt() {
        PayabliSession.setLogLevel(LogLevel.NONE)

        runBlocking { PayabliSession.initialize(config(), HostBindings(context)).getOrThrow() }

        // Why the setter is on the companion at all. An instance-owned one could not have been called
        // before this point, so the automatic value would always be the later write and would always win.
        assertEquals(LogLevel.NONE, LoggerRegistry.effectiveLogLevel())
    }

    @Test
    fun repeatedInitializeKeepsDerivingTheSameLevel() {
        runBlocking {
            PayabliSession.initialize(config(), HostBindings(context)).getOrThrow()
            PayabliSession.initialize(config(), HostBindings(context)).getOrThrow()
        }

        // Idempotent returns the installed session without rebuilding it, and the derivation runs on the
        // way in regardless. Both are safe to repeat, which is what makes it callable from onCreate.
        assertEquals(LogLevel.DEBUG, LoggerRegistry.effectiveLogLevel())
    }
}
