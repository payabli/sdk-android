package com.payabli.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.example.app.InstrumentedSession
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.SdkState
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That an app naming only the payment artifacts gets reporting, proven on a real build of one.
 *
 * This app depends on `:payin` and `:taptopay` and never names the telemetry module, which is the position of an
 * integrator who takes either payment artifact without the umbrella. Every payment artifact brings the module, so
 * it is on the classpath and starts with the session.
 */
@RunWith(AndroidJUnit4::class)
class TelemetryLinkageInstrumentedTest {
    @Test
    fun thePaymentArtifactsBringTheReportingModule() {
        Class.forName(TELEMETRY_MODULE)
    }

    /**
     * Every test here owns its precondition.
     *
     * `reset` is internal to `:core`, so the first install in this process decides what the rest get and a
     * later `initialize` naming a different configuration is refused. Installing here rather than leaning on
     * a sibling test means no method in this class depends on running after another, which JUnit does not
     * promise.
     */
    @Before
    fun installTheSharedSession() {
        val host = HostBindings(InstrumentationRegistry.getInstrumentation().targetContext)

        installed =
            runBlocking {
                PayabliSession.initialize(sharedConfiguration(), host)
            }
    }

    @Test
    fun theSdkInitializesAndIsUsable() {
        assertTrue("initialize failed", installed != null)
        assertEquals(SdkState.Ready, PayabliSession.state.value)
    }

    /**
     * What a capability does when a card-not-present submission finishes.
     *
     * This is the call `PayInSubmission.report` makes, not an imitation of it: same function, same event,
     * same shape of properties. The submission entry points are internal — a host reaches them through the
     * Compose form — so driving a real payment here would need a network and a form, and would test the form
     * rather than this. What matters is that the seam reaches the recorder the session started, and that is
     * this function.
     */
    @Test
    fun aCompletedCardNotPresentFlowReachesTheRecorder() {
        assertTrue("the session this assertion rests on was not installed", installed != null)
        var propertiesBuilt = 0

        @Suppress("RestrictedApi")
        TelemetryRecorders.record(TelemetryEvents.PAYIN_CAPTURE_COMPLETED) {
            propertiesBuilt++
            mapOf(
                TelemetryProperty.OUTCOME.key to TelemetryProperties.Outcome.APPROVED,
                TelemetryProperty.DURATION_MS.key to "18",
            )
        }

        assertEquals("the properties should be built once", 1, propertiesBuilt)
    }

    /**
     * The one configuration every instrumented test in this application installs.
     *
     * Every value the SDK compares is here. A provider is required, so its presence cannot distinguish two
     * configurations and is not compared. `InstrumentedSession` holds the two the sibling classes also name.
     */
    private fun sharedConfiguration() =
        PayabliConfig(
            entryPoint = InstrumentedSession.ENTRY_POINT,
            environment = InstrumentedSession.ENVIRONMENT.sdkEnvironment,
            tokenProvider = { "a-token-for-this-test" },
        )

    private var installed: PayabliSession? = null

    private companion object {
        const val TELEMETRY_MODULE = "com.payabli.sdk.telemetry.TelemetryModule"
    }
}
