package com.payabli.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.example.app.BuildConfig
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
 * That the SDK works whether or not the reporting artifact was linked, proven on a real build of each.
 *
 * The two flavors differ in exactly one dependency, so this runs twice and asserts opposite things about the
 * classpath and the same thing about the SDK. Simulating the absence from inside a test can reach the code
 * path; only a build that genuinely omits the artifact can show what an integrator depending on `:core`
 * alone actually gets.
 *
 * The assertion that matters is the one both flavors share: **initialization succeeds and the session is
 * usable either way**. Reporting is something the SDK does for itself, and an app that never asked for it
 * must not be able to tell.
 */
@RunWith(AndroidJUnit4::class)
class TelemetryLinkageInstrumentedTest {
    @Test
    fun theFlavorLinksTheReportingArtifactOrDoesNot() {
        val onClasspath =
            try {
                Class.forName(TELEMETRY_MODULE)
                true
            } catch (_: ClassNotFoundException) {
                false
            }

        assertEquals(
            "BuildConfig.FLAVOR is ${BuildConfig.FLAVOR}, which disagrees with what is on the classpath",
            linked(),
            onClasspath,
        )
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
    fun theSdkInitializesAndIsUsableEitherWay() {
        assertTrue("initialize failed on flavor ${BuildConfig.FLAVOR}", installed?.isSuccess == true)
        assertEquals(SdkState.Ready, PayabliSession.state.value)
    }

    /**
     * What a capability does when a card-not-present submission finishes.
     *
     * This is the call `PayInSubmission.report` makes, not an imitation of it: same function, same event,
     * same shape of properties. The submission entry points are internal — a host reaches them through the
     * Compose form — so driving a real payment here would need a network and a form, and would test the form
     * rather than this. What matters is what the emitting seam does when nothing is listening, and that is
     * this function.
     *
     * The assertion differs by flavor. With nothing linked the properties are **never built**, which is the
     * claim that an app that did not ask for reporting pays nothing for it — and it is a claim about a real
     * build here rather than about a mocked-out registry.
     */
    @Test
    fun aCompletedCardNotPresentFlowReportsOrCostsNothing() {
        assertTrue("the session this assertion rests on was not installed", installed?.isSuccess == true)
        var propertiesBuilt = 0

        @Suppress("RestrictedApi")
        TelemetryRecorders.record(TelemetryEvents.PAYIN_CAPTURE_COMPLETED) {
            propertiesBuilt++
            mapOf(
                TelemetryProperty.OUTCOME.key to TelemetryProperties.Outcome.APPROVED,
                TelemetryProperty.DURATION_MS.key to "18",
            )
        }

        assertEquals(
            "on ${BuildConfig.FLAVOR} the properties should " +
                if (linked()) "be built once" else "never be built",
            if (linked()) 1 else 0,
            propertiesBuilt,
        )
    }

    /**
     * That the card-not-present artifact loads at all with nothing linked.
     *
     * The seam lives in `:core`, so a capability never names the telemetry module — but "never names it" is
     * a property of the source that a build can lose. Loading and initialising the class is what would raise
     * `NoClassDefFoundError` if it ever gained a reference.
     */
    @Test
    fun theCardNotPresentCapabilityLoadsWithNothingLinked() {
        Class.forName(PAY_IN_FLOW, true, javaClass.classLoader)
    }

    private fun linked(): Boolean = BuildConfig.FLAVOR == "withTelemetry"

    /**
     * The one configuration every instrumented test in this application installs.
     *
     * All four values the SDK compares are here, including whether a token provider was supplied: the
     * comparison counts its presence, so a config without one is a different configuration however well the
     * other three agree. `InstrumentedSession` holds the two the sibling classes also name.
     */
    private fun sharedConfiguration() =
        PayabliConfig(
            accessToken = "a-token-for-this-test",
            entryPoint = InstrumentedSession.ENTRY_POINT,
            environment = InstrumentedSession.ENVIRONMENT.sdkEnvironment,
            tokenProvider = { "a-token-for-this-test" },
        )

    private var installed: Result<PayabliSession>? = null

    private companion object {
        const val TELEMETRY_MODULE = "com.payabli.sdk.telemetry.TelemetryModule"
        const val PAY_IN_FLOW = "com.payabli.sdk.payin.PayInPaymentFlow"
    }
}
