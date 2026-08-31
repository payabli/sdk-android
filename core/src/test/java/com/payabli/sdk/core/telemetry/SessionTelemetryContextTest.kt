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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a reporting channel is allowed to learn about a session.
 *
 * The session holds the configuration and publishes none of it, so without this a sibling artifact has no way
 * to know which entry point and environment it is reporting for, and no identifier for the run.
 */
class SessionTelemetryContextTest {
    @After
    fun restoreProcessWideState() {
        runBlocking { PayabliSession.reset() }
        assertEquals(SdkState.Uninitialized, PayabliSession.state.value)
    }

    @Test
    fun theContextCarriesWhatEveryEventHasToReport() =
        runTest {
            val session = install(entryPoint = "an-entry-point", telemetryEnabled = true)

            assertEquals("an-entry-point", session.telemetry.entryPoint)
            assertEquals(PayabliEnvironment.SANDBOX, session.telemetry.environment)
            assertTrue(session.telemetry.telemetryEnabled)
        }

    @Test
    fun theHostsOptOutReachesTheChannel() =
        runTest {
            assertFalse(install(telemetryEnabled = false).telemetry.telemetryEnabled)
        }

    /** It identifies one SDK lifetime, so it has to be fresh per install rather than per device or install. */
    @Test
    fun eachSessionGetsItsOwnIdentifier() =
        runTest {
            val first = install().telemetry.sessionId
            PayabliSession.reset()
            val second = install().telemetry.sessionId

            assertNotEquals(first, second)
        }

    @Test
    fun theIdentifierIsAShapeThatCanBeReported() =
        runTest {
            val sessionId = install().telemetry.sessionId

            assertTrue(sessionId, Regex("^[A-Za-z0-9_-]{8,64}$").matches(sessionId))
        }

    /** The entry point names a specific merchant, and this string reaches exception messages and reports. */
    @Test
    fun theContextWithholdsTheEntryPointFromItsOwnDescription() =
        runTest {
            val context = install(entryPoint = "a-named-merchant").telemetry

            assertFalse(context.toString().contains("a-named-merchant"))
        }

    private suspend fun install(
        entryPoint: String = "an-entry-point",
        telemetryEnabled: Boolean = true,
    ): PayabliSession =
        PayabliSession
            .initializeWith(
                PayabliConfig(
                    accessToken = "a-token",
                    entryPoint = entryPoint,
                    environment = PayabliEnvironment.SANDBOX,
                    telemetryEnabled = telemetryEnabled,
                ),
            ) { UnusedTransport }
            .getOrThrow()

    /** No request is made by any of these; the session only has to be installed. */
    private object UnusedTransport : PayabliTransport {
        override suspend fun execute(request: PayabliRequest): PayabliResponse =
            throw UnsupportedOperationException("no request is made")

        override suspend fun <T> execute(
            request: PayabliRequest,
            payloadSerializer: KSerializer<T>,
        ): PayabliV2Envelope<T> = throw UnsupportedOperationException("no request is made")
    }
}
