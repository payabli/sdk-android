package com.payabli.sdk.core.telemetry

import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.SdkState
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reporting starts with the session, or not at all.
 *
 * `:core`'s test classpath carries the telemetry module, so these run the case an integrator gets: the
 * session finds it and starts reporting without anything being called. The absent case — every app that did
 * not link it — is covered by the lookup returning null, which cannot be shown from here and is what
 * `TelemetryModuleDiscoveryTest` in that module pins from the other side.
 */
class TelemetryBootstrapTest {
    @After
    fun restoreProcessWideState() {
        runBlocking { PayabliSession.reset() }
        TelemetryBootstraps.implementation = TelemetryBootstrap.IMPLEMENTATION
        TelemetryBootstraps.forget()
        assertEquals(SdkState.Uninitialized, PayabliSession.state.value)
    }

    /** Every app that did not link the module, which is the common case rather than an edge one. */
    private fun withoutTheModule() {
        TelemetryBootstraps.implementation = "com.payabli.sdk.telemetry.NotOnThisClasspath"
        TelemetryBootstraps.forget()
    }

    @Test
    fun aSessionStartsNormallyWithTheModuleLinked() =
        runTest {
            val session = install()

            assertEquals(SdkState.Ready, PayabliSession.state.value)
            assertEquals("an-entry-point", session.telemetry.entryPoint)
        }

    /**
     * The build an integrator gets when they depend on `:core` and nothing else.
     *
     * The lookup misses, no recorder is installed, and every one of these has to be a no-op rather than a
     * failure: the SDK's job does not depend on reporting existing.
     */
    @Test
    fun anAbsentModuleLeavesTheSdkWorking() =
        runTest {
            withoutTheModule()

            assertNull("a module was found on a classpath that has none", TelemetryBootstraps.installed())

            val session = install()

            assertEquals(SdkState.Ready, PayabliSession.state.value)
            assertEquals("an-entry-point", session.telemetry.entryPoint)
        }

    /** The emitting sites run unchanged whether or not anything is listening. */
    @Test
    fun emittingWithNoModuleLinkedDoesNothingAndThrowsNothing() =
        runTest {
            withoutTheModule()
            install()

            TelemetryRecorders.record(TelemetryEvents.PAYIN_CAPTURE_COMPLETED) {
                mapOf(TelemetryProperties.OUTCOME to TelemetryProperties.Outcome.APPROVED)
            }
        }

    /**
     * The present case, which is the whole of the auto-start design: linking the artifact is the integration.
     *
     * `:core`'s test classpath carries `:telemetry` for this one assertion. Without it the lookup can only
     * ever be shown to miss, and a rename or a moved package would pass every test here while shipping an SDK
     * that reports nothing.
     */
    @Test
    fun theModuleIsFoundWhenItIsLinked() {
        val found = TelemetryBootstraps.installed()

        assertNotNull("the telemetry module is on this classpath and was not found", found)
        assertEquals(TelemetryBootstrap.IMPLEMENTATION, found!!.javaClass.name)
    }

    /** Absent is the common case, so the miss is what must not be paid for on every initialize. */
    @Test
    fun theAnswerIsLookedUpOnceAndKept() {
        val first = TelemetryBootstraps.installed()
        val second = TelemetryBootstraps.installed()

        assertEquals(first, second)
    }

    private suspend fun install(): PayabliSession =
        PayabliSession
            .initializeWith(
                PayabliConfig(
                    accessToken = "a-token",
                    entryPoint = "an-entry-point",
                    environment = PayabliEnvironment.SANDBOX,
                ),
            ) { UnusedTransport }
            .getOrThrow()

    private object UnusedTransport : PayabliTransport {
        override suspend fun execute(request: PayabliRequest): PayabliResponse =
            throw UnsupportedOperationException("no request is made")

        override suspend fun <T> execute(
            request: PayabliRequest,
            payloadSerializer: KSerializer<T>,
        ): PayabliV2Envelope<T> = throw UnsupportedOperationException("no request is made")
    }
}
