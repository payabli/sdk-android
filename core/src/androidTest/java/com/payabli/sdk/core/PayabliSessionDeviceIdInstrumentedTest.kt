package com.payabli.sdk.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.device.platform.DeviceIdentifierFactory
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.core.logging.LoggerRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** That the session publishes the identity device registration and telemetry already send, on a real handset. */
@RunWith(AndroidJUnit4::class)
class PayabliSessionDeviceIdInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun config() =
        PayabliConfig(
            entryPoint = "entry",
            environment = PayabliEnvironment.SANDBOX,
            tokenProvider = { "instrumented-token" },
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
    }

    @Test
    fun theDeviceIdIsTheIdentityRegistrationSends() {
        val session = runBlocking { PayabliSession.initialize(config(), HostBindings(context)) }

        val deviceId = session.deviceId
        assertNotNull("a handset with a platform identifier has an identity", deviceId)
        assertTrue("not the 32 lowercase hex digest", Regex("[0-9a-f]{32}").matches(deviceId!!))
        assertEquals(DeviceIdentifierFactory.of(context), deviceId)
    }

    @Test
    fun theDeviceIdSurvivesANewSession() {
        val first = runBlocking { PayabliSession.initialize(config(), HostBindings(context)).deviceId }
        runBlocking { PayabliSession.reset() }
        val second = runBlocking { PayabliSession.initialize(config(), HostBindings(context)).deviceId }

        assertNotNull(first)
        assertEquals(first, second)
    }
}
