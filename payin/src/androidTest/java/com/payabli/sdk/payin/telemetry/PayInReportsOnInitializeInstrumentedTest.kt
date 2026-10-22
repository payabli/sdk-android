package com.payabli.sdk.payin.telemetry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.payin.payment.TEST_ENTRY_POINT
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An app that depends on this artifact alone starts reporting when it initializes, with nothing else to call.
 *
 * Only the telemetry module installs a recorder when a session starts, so a recorder being there after the public
 * `initialize` is the module having started. That start queues `sdk.initialized` first, which `:core`'s
 * `TelemetryBootstrapTest` and `TelemetryOnDeviceTest` cover.
 */
@RunWith(AndroidJUnit4::class)
class PayInReportsOnInitializeInstrumentedTest {
    @After
    fun clear() {
        TelemetryRecorders.clear()
    }

    @Test
    fun initializingStartsReporting() {
        TelemetryRecorders.clear()

        runBlocking {
            PayabliSession.initialize(
                PayabliConfig(
                    entryPoint = TEST_ENTRY_POINT,
                    environment = PayabliEnvironment.SANDBOX,
                    tokenProvider = { "a-token-for-this-test" },
                ),
                HostBindings(InstrumentationRegistry.getInstrumentation().targetContext),
            )
        }

        var built = 0
        TelemetryRecorders.record(TelemetryEvents.PAYIN_CAPTURE_COMPLETED) {
            built++
            emptyMap()
        }

        assertEquals("no recorder was installed by initialize", 1, built)
    }
}
