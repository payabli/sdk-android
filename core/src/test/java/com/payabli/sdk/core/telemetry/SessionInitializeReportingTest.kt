package com.payabli.sdk.core.telemetry

import com.payabli.sdk.core.PayabliSession
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What `initialize` reports, and what it does not.
 *
 * A start with no outcome after it is a funnel nobody can close, and the ordinary way to produce one is an
 * Application and an Activity both initializing: the second call changes nothing.
 *
 * Nothing is listening on a first install or after a reset, because the channel is what a successful install
 * creates, so what these cover is a repeat call against a live session.
 */
class SessionInitializeReportingTest {
    private val recorded = mutableListOf<String>()

    @Before
    fun listen() {
        // The real module is on this module's test classpath and installs its own recorder as a session
        // starts, which would replace the one below. Pointed at a name that resolves to nothing, it does not.
        TelemetryBootstraps.implementation = "com.payabli.sdk.telemetry.NotOnThisClasspath"
        TelemetryBootstraps.forget()
        TelemetryRecorders.install { event, _ -> recorded += event }
    }

    @After
    fun restoreProcessWideState() {
        TelemetryRecorders.clear()
        TelemetryBootstraps.implementation = TelemetryBootstrap.IMPLEMENTATION
        TelemetryBootstraps.forget()
        runBlocking { PayabliSession.reset() }
    }

    @Test
    fun aRepeatCallThatChangesNothingReportsNothing() =
        runTest {
            install(entryPoint = "an-entry-point")
            recorded.clear()

            install(entryPoint = "an-entry-point")

            assertEquals(emptyList<String>(), recorded)
        }

    /** And the repeat that is refused reports both halves, so the start it emitted is answered. */
    @Test
    fun aRefusedRepeatCallReportsAStartAndAFailure() =
        runTest {
            install(entryPoint = "an-entry-point")
            recorded.clear()

            val refused = initialize(entryPoint = "a-different-entry-point")

            assertTrue("the second configuration was accepted", refused.isFailure)
            assertEquals(
                listOf(TelemetryEvents.SDK_INITIALIZE_STARTED, TelemetryEvents.SDK_INITIALIZE_FAILED),
                recorded,
            )
        }

    private suspend fun install(entryPoint: String): PayabliSession = initialize(entryPoint).getOrThrow()

    private suspend fun initialize(entryPoint: String): Result<PayabliSession> =
        PayabliSession.initializeWith(
            PayabliConfig(
                accessToken = "a-token",
                entryPoint = entryPoint,
                environment = PayabliEnvironment.SANDBOX,
            ),
        ) { UnusedTransport }

    private object UnusedTransport : PayabliTransport {
        override suspend fun execute(request: PayabliRequest): PayabliResponse =
            throw UnsupportedOperationException("no request is made")

        override suspend fun <T> execute(
            request: PayabliRequest,
            payloadSerializer: KSerializer<T>,
        ): PayabliV2Envelope<T> = throw UnsupportedOperationException("no request is made")
    }
}
