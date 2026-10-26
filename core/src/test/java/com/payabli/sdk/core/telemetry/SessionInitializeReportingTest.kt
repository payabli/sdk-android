package com.payabli.sdk.core.telemetry

import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.SdkState
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.config.PayabliTokenProvider
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.model.RedactedFailure
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import com.payabli.sdk.core.network.impl.AuthFailureListener
import kotlinx.coroutines.CancellationException
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
 * The reporting channel is what a successful install creates, so nothing is listening on a first
 * install: what these cover is a repeat call against a live session, and the converted failure.
 */
class SessionInitializeReportingTest {
    private val recorded = mutableListOf<String>()
    private val recordedProperties = mutableListOf<Map<String, String>>()

    @Before
    fun listen() {
        // The real module is on this module's test classpath and installs its own recorder as a session
        // starts, which would replace the one below. Pointed at a name that resolves to nothing, it does not.
        TelemetryBootstraps.implementation = "com.payabli.sdk.telemetry.NotOnThisClasspath"
        TelemetryBootstraps.forget()
        TelemetryRecorders.install { event, properties ->
            recorded += event
            recordedProperties += properties
        }
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

            val refused = runCatching { install(entryPoint = "a-different-entry-point") }

            assertTrue("the second configuration was accepted", refused.isFailure)
            assertEquals(
                listOf(TelemetryEvents.SDK_INITIALIZE_STARTED, TelemetryEvents.SDK_INITIALIZE_FAILED),
                recorded,
            )
        }

    /**
     * A failure outside the refusals converts, and the pair it reports names the type that failed.
     *
     * The converting catch sits in `install`, the half of `initialize` these tests reach without host
     * bindings.
     */
    @Test
    fun aFailureOutsideTheRefusalsIsConvertedAndItsPairNamesTheTypeThatFailed() =
        runTest {
            class Foreign : RuntimeException("a message the SDK must not carry out")

            val foreign = Foreign()

            val thrown =
                runCatching {
                    PayabliSession.initializeWith(configFor("an-entry-point")) { _ -> throw foreign }
                }

            val converted = thrown.exceptionOrNull()
            assertTrue("the foreign failure reached the caller unconverted", converted is PayabliGenericException)
            converted as PayabliGenericException
            assertEquals(PayabliErrorType.UNKNOWN, converted.type)
            assertEquals(PayabliErrorType.UNKNOWN.message, converted.reason)
            assertTrue("the original is not kept as a redacted cause", converted.cause is RedactedFailure)
            assertEquals(foreign.javaClass.name, converted.cause?.message)
            assertEquals(
                listOf(TelemetryEvents.SDK_INITIALIZE_STARTED, TelemetryEvents.SDK_INITIALIZE_FAILED),
                recorded,
            )
            assertEquals(mapOf(TelemetryProperty.STATE.key to "uninitialized"), recordedProperties[0])
            val reported = recordedProperties[1]
            assertEquals(TelemetryProperties.Outcome.FAILED, reported[TelemetryProperty.OUTCOME.key])
            assertEquals(PayabliErrorType.UNKNOWN.wireName, reported[TelemetryProperty.CODE.key])
            assertEquals(PayabliErrorType.UNKNOWN.code.toString(), reported[TelemetryProperty.ERROR_NUMBER.key])
            assertEquals(PayabliErrorType.UNKNOWN.message, reported[TelemetryProperty.REASON.key])
            assertEquals("Foreign", reported[TelemetryProperty.FAILURE_TYPE.key])
            assertTrue(
                "duration_ms is missing or not a number",
                reported[TelemetryProperty.DURATION_MS.key]?.toLongOrNull() != null,
            )
        }

    /**
     * A withdrawn initialize is not a failure: it passes through as `CancellationException` and reports
     * nothing, because converting it would both hide the withdrawal from the caller that is unwinding
     * and report an outcome for one.
     */
    @Test
    fun aWithdrawnInitializePassesThroughUnconvertedAndReportsNothing() =
        runTest {
            // Raised by the attempt rather than the job being cancelled from outside, which reaches the
            // converting catch as the same value either way.
            val outcome =
                runCatching {
                    PayabliSession.initializeWith(configFor("an-entry-point")) { _ ->
                        throw CancellationException("the host withdrew")
                    }
                }.exceptionOrNull()

            assertTrue("a withdrawal was converted: $outcome", outcome is CancellationException)
            assertEquals(emptyList<String>(), recorded)
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
                }

            retire!!.onUnrecoverable(PayabliGenericException(PayabliErrorType.TOKEN_EXPIRED, "no longer valid"))
            assertEquals(SdkState.ReinitializeRequired, PayabliSession.state.value)
            recorded.clear()

            install(entryPoint = "a-different-entry-point")

            // The recorder standing here is the retired session's: the replacement's own channel is created
            // by the install that has not finished yet, which is what made the start land in the wrong one.
            assertEquals(emptyList<String>(), recorded)
        }

    private suspend fun install(entryPoint: String): PayabliSession =
        PayabliSession.initializeWith(configFor(entryPoint)) { UnusedTransport }

    private fun configFor(entryPoint: String) =
        PayabliConfig(
            entryPoint = entryPoint,
            environment = PayabliEnvironment.SANDBOX,
            tokenProvider = PayabliTokenProvider { "a-minted-token" },
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
