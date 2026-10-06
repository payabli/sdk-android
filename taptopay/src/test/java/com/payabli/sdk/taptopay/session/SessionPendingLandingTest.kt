package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.taptopay.TapToPayErrorCodes
import com.payabli.sdk.taptopay.enrollment.DEVICE_ID
import com.payabli.sdk.taptopay.enrollment.RouteScript
import com.payabli.sdk.taptopay.enrollment.challengeBody
import com.payabli.sdk.taptopay.enrollment.decline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        }
}
