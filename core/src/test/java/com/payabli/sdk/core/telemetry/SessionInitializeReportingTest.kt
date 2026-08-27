package com.payabli.sdk.core.telemetry

import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.SdkState
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import com.payabli.sdk.core.network.impl.AuthFailureListener
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

    /**
     * A replacement reports no start, because the only channel it could reach is the wrong one.
     *
     * `ReinitializeRequired` is the documented recovery, and it retires the session while leaving that
     * session's reporting channel installed: the new one is not created until the install succeeds. A start
     * recorded on the way through therefore left with the retired session's id and entry point while
     * `sdk.initialized` left with the new one, so the pair described two sessions and, where the entry point
     * changed, two merchants.
     */
    @Test
    fun aReplacementAfterTheSessionRetiredReportsNoStart() =
        runTest {
            var retire: AuthFailureListener? = null
            PayabliSession
                .initializeWith(configFor("an-entry-point")) { onAuthFailure ->
                    retire = onAuthFailure
                    UnusedTransport
                }.getOrThrow()

            retire!!.onUnrecoverable(PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, "no longer valid"))
            assertEquals(SdkState.ReinitializeRequired, PayabliSession.state.value)
            recorded.clear()

            install(entryPoint = "a-different-entry-point")

            // The recorder standing here is the retired session's: the replacement's own channel is created
            // by the install that has not finished yet, which is what made the start land in the wrong one.
            assertEquals(emptyList<String>(), recorded)
        }

    private suspend fun install(entryPoint: String): PayabliSession = initialize(entryPoint).getOrThrow()

    private suspend fun initialize(entryPoint: String): Result<PayabliSession> =
        PayabliSession.initializeWith(configFor(entryPoint)) { UnusedTransport }

    private fun configFor(entryPoint: String) =
        PayabliConfig(
            accessToken = "a-token",
            entryPoint = entryPoint,
            environment = PayabliEnvironment.SANDBOX,
        )

    private object UnusedTransport : PayabliTransport {
        override suspend fun execute(request: PayabliRequest): PayabliResponse =
            throw UnsupportedOperationException("no request is made")

        override suspend fun <T> execute(
            request: PayabliRequest,
            payloadSerializer: KSerializer<T>,
        ): PayabliV2Envelope<T> = throw UnsupportedOperationException("no request is made")
    }
}
