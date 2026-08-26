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

    /**
     * A module that throws while starting must not fail the initialize that started it.
     *
     * This runs after the session is published and marked ready, so an escaping failure produced the worst
     * shape available: the first `initialize` throws, and the next one returns the session the first had
     * already installed. Both kinds are covered because they are caught separately: a `RuntimeException`
     * from the module's own code, and an `Error` from a symbol that resolves only once `start` runs, which
     * is what a stripped or mismatched artifact does.
     */
    @Test
    fun aModuleThatThrowsWhileStartingDoesNotFailTheSession() =
        runTest {
            listOf(
                BootstrapThatThrowsOnStart::class.java.name,
                BootstrapThatFailsToLink::class.java.name,
                BootstrapThatThrowsOnStartAndStop::class.java.name,
            ).forEach { hostile ->
                PayabliSession.reset()
                TelemetryBootstraps.implementation = hostile
                TelemetryBootstraps.forget()

                val session = install()

                assertEquals(hostile, SdkState.Ready, PayabliSession.state.value)
                assertEquals(hostile, "an-entry-point", session.telemetry.entryPoint)
            }
        }

    /**
     * A module that failed to start is forgotten rather than retried on every initialize.
     *
     * Left cached it would throw once per session install for the life of the process, and the unwind would
     * run against a channel that never started.
     */
    @Test
    fun aModuleThatFailedToStartIsNotAskedAgain() =
        runTest {
            TelemetryBootstraps.implementation = BootstrapThatThrowsOnStart::class.java.name
            TelemetryBootstraps.forget()

            install()

            assertNull("a module that threw while starting is still installed", TelemetryBootstraps.installed())
        }

    /** That the assertions above are not passing because nothing is being started at all. */
    @Test
    fun aModuleThatStartsCleanlyIsStartedExactlyOnce() =
        runTest {
            CountingBootstrap.reset()
            TelemetryBootstraps.implementation = CountingBootstrap::class.java.name
            TelemetryBootstraps.forget()

            install()

            assertEquals(1, CountingBootstrap.starts)
            assertEquals(0, CountingBootstrap.stops)
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
