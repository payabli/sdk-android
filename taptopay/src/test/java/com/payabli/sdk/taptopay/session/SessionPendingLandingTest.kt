package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.taptopay.TapToPayErrorCodes
import com.payabli.sdk.taptopay.enrollment.DEVICE_ID
import com.payabli.sdk.taptopay.enrollment.RouteScript
import com.payabli.sdk.taptopay.enrollment.challengeBody
import com.payabli.sdk.taptopay.enrollment.decline
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

private suspend fun failureOf(block: suspend () -> Unit): Throwable =
    runCatching { block() }.exceptionOrNull() ?: throw AssertionError("expected the call to fail")

private const val NOT_ACTIVE = "Device is not active."

/**
 * Where a refusal that says the device owes activation lands, read against what this device has stored.
 *
 * The state and the failure a caller is given are asserted together, because a host reads one or the other
 * and the two must name the same remedy.
 */
class SessionPendingLandingTest {
    private val recorded = mutableListOf<Pair<String, Map<String, String>>>()

    @Before
    fun install() {
        TelemetryRecorders.install { event, properties -> recorded += event to properties }
    }

    @After
    fun clear() {
        TelemetryRecorders.clear()
    }

    /** The catalog number each failure event reported, by event. */
    private fun reportedNumbers(): Map<String, String?> =
        recorded
            .filter { (event, _) ->
                event == TelemetryEvents.TTP_INITIALIZE_FAILED || event == TelemetryEvents.TTP_ATTESTATION_FAILED
            }.associate { (event, properties) -> event to properties[TelemetryProperty.ERROR_NUMBER.key] }

    @Test
    fun `a stored registration lands pending activation, carrying its id`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = SessionFixture(RouteScript(RouteScript.CONFIG to listOf(decline(403, NOT_ACTIVE))))
            fixture.seedRecord()

            val failure = failureOf { fixture.coordinator.initialize() }

            assertEquals(TapToPaySessionState.PendingActivation(DEVICE_ID), fixture.state)
            assertEquals(PayabliErrorType.DEVICE_PENDING_ACTIVATION, TapToPayErrorCodes.typeFor(failure))
        }

    @Test
    fun `a refusal before anything is registered lands as the paypoint's configuration`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(decline(403, NOT_ACTIVE)),
                    ),
                )

            val failure = failureOf { fixture.coordinator.initialize() }

            assertEquals(TapToPaySessionState.Failed(TapToPayFailureReason.CONFIGURATION_REJECTED), fixture.state)
            assertEquals(PayabliErrorType.PERMISSION_DENIED, TapToPayErrorCodes.typeFor(failure))
            // Telemetry reports the failure the host was given, on the phase and on initialize.
            val denied = PayabliErrorType.PERMISSION_DENIED.code.toString()
            assertEquals(
                mapOf(
                    TelemetryEvents.TTP_ATTESTATION_FAILED to denied,
                    TelemetryEvents.TTP_INITIALIZE_FAILED to denied,
                ),
                reportedNumbers(),
            )
        }

    @Test
    fun `a registration the store will not read lands as storage being unavailable`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The store answers until the service has refused, so the refusal is reached through a stored
            // registration and only the read that decides the landing is refused.
            lateinit var fixture: SessionFixture
            fixture =
                SessionFixture(
                    RouteScript(RouteScript.CONFIG to listOf(decline(403, NOT_ACTIVE))),
                    storeFailure = { operation, _ ->
                        SecureStorageException.CryptoUnavailable().takeIf {
                            operation == "get" && RouteScript.CONFIG in fixture.routes
                        }
                    },
                )
            fixture.seedRecord()

            val failure = failureOf { fixture.coordinator.initialize() }

            assertEquals(TapToPaySessionState.Failed(TapToPayFailureReason.DEVICE_KEY_UNAVAILABLE), fixture.state)
            assertEquals(PayabliErrorType.DEVICE_KEY_UNAVAILABLE, TapToPayErrorCodes.typeFor(failure))
            assertEquals(
                PayabliErrorType.DEVICE_KEY_UNAVAILABLE.code.toString(),
                reportedNumbers()[TelemetryEvents.TTP_INITIALIZE_FAILED],
            )
        }

    @Test
    fun `a failure that does not owe activation reads nothing from the store`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The store refuses every read once the service has answered, so a read here would be logged.
            lateinit var fixture: SessionFixture
            fixture =
                SessionFixture(
                    RouteScript(RouteScript.CONFIG to listOf(decline(500, "Internal error."))),
                    storeFailure = { operation, _ ->
                        SecureStorageException.CryptoUnavailable().takeIf {
                            operation == "get" && RouteScript.CONFIG in fixture.routes
                        }
                    },
                )
            fixture.seedRecord()

            failureOf { fixture.coordinator.initialize() }

            assertEquals(TapToPaySessionState.Failed(TapToPayFailureReason.SERVICE_UNAVAILABLE), fixture.state)
            assertFalse(
                fixture.enrollment.logger.records
                    .toString(),
                fixture.enrollment.logger.records
                    .any { it.message.contains("registration could not be read") },
            )
        }
}
