package com.payabli.sdk.taptopay.session

import com.payabli.sdk.taptopay.enrollment.RouteScript
import com.payabli.sdk.taptopay.enrollment.configBody
import com.payabli.sdk.taptopay.enrollment.decline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private suspend fun failureOf(block: suspend () -> Unit): Throwable? = runCatching { block() }.exceptionOrNull()

/**
 * A device that was attested on an earlier run, coming back.
 *
 * The property worth proving is an absence: the cold sequence does not run again. It is asserted on the
 * route trace rather than on a flag, and the script answers only `/config`, so an attestation re-run fails
 * by naming the route that was not scripted rather than by an assertion nobody wrote.
 */
class SessionWarmStartTest {
    @Test
    fun `a warm start reaches pending activation through config, with no attestation re-run`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    RouteScript(RouteScript.CONFIG to listOf(decline(403, "Device is not active."))),
                )
            fixture.seedRecord()

            val failure = failureOf { fixture.coordinator.initialize() }

            assertTrue("$failure", failure is TapToPaySessionException.PendingActivation)
            assertEquals(TapToPaySessionState.PendingActivation, fixture.state)
            assertEquals(listOf(RouteScript.CONFIG), fixture.routes)
            assertEquals("the platform was never asked for a verdict", 0, fixture.enrollment.attestor.challenges.size)
            assertEquals("the reader was never reached", 0, fixture.reader.configureCount)
        }

    @Test
    fun `a warm start on an active device is ready without attesting`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = SessionFixture(RouteScript(RouteScript.CONFIG to listOf(configBody())))
            fixture.seedRecord()

            fixture.coordinator.initialize()

            assertEquals(TapToPaySessionState.Ready, fixture.state)
            assertEquals(listOf(RouteScript.CONFIG), fixture.routes)
            assertEquals(0, fixture.enrollment.attestor.challenges.size)
            assertEquals("pp-id-value", fixture.reader.lastCredentials?.ppId)
        }

    @Test
    fun `a rotated credential discards the binding and fails the attempt, without re-attesting`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CONFIG to
                            listOf(decline(401, "Device not attested or attestation revoked.")),
                    ),
                )
            fixture.seedRecord()

            val failure = failureOf { fixture.coordinator.initialize() }

            assertTrue("$failure", failure is TapToPaySessionException.AttestationRequired)
            assertEquals(
                TapToPaySessionState.Failed(TapToPayFailureReason.ATTESTATION_REQUIRED),
                fixture.state,
            )
            assertEquals("the record names a binding that is gone", null, fixture.enrollment.storedRecord())
            assertEquals("no second attempt from inside the failing call", listOf(RouteScript.CONFIG), fixture.routes)
        }

    @Test
    fun `a repair on a device that owes a code reaches pending activation without attesting`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    RouteScript(RouteScript.CONFIG to listOf(decline(403, "Device is not active."))),
                )
            fixture.seedRecord()

            val failure = failureOf { fixture.coordinator.reinitializeIfNeeded() }

            assertTrue("$failure", failure is TapToPaySessionException.PendingActivation)
            assertEquals(TapToPaySessionState.PendingActivation, fixture.state)
            assertEquals(listOf(RouteScript.CONFIG), fixture.routes)
        }

    @Test
    fun `a repair does nothing to a ready session`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = SessionFixture(RouteScript(RouteScript.CONFIG to listOf(configBody())))
            fixture.seedRecord()
            fixture.coordinator.initialize()

            // One answer is scripted, so a second fetch fails by name rather than by an assertion.
            fixture.coordinator.reinitializeIfNeeded()

            assertEquals(TapToPaySessionState.Ready, fixture.state)
            assertEquals(listOf(RouteScript.CONFIG), fixture.routes)
            assertEquals(1, fixture.reader.prepareCount)
        }

    @Test
    fun `a build from a session that is already up runs every phase again`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(RouteScript(RouteScript.CONFIG to listOf(configBody(), configBody())))
            fixture.seedRecord()
            fixture.coordinator.initialize()
            assertEquals(TapToPaySessionState.Ready, fixture.state)

            fixture.coordinator.initialize()

            // Nothing in the table lets a ready session reach attestation or config directly. Building one
            // starts from the beginning whatever the caller left behind, which is what makes this legal.
            assertEquals(TapToPaySessionState.Ready, fixture.state)
            assertEquals(listOf(RouteScript.CONFIG, RouteScript.CONFIG), fixture.routes)
            assertEquals(2, fixture.reader.prepareCount)
        }

    @Test
    fun `a repair refuses a session it cannot repair, and names the state`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    RouteScript(RouteScript.CONFIG to listOf(decline(403, "Device is not active."))),
                )
            fixture.seedRecord()
            failureOf { fixture.coordinator.initialize() }
            assertEquals(TapToPaySessionState.PendingActivation, fixture.state)

            val failure = failureOf { fixture.coordinator.reinitializeIfNeeded() }

            assertTrue("$failure", failure is TapToPaySessionException.NotRecoverable)
            assertEquals(
                TapToPaySessionState.PendingActivation,
                (failure as TapToPaySessionException.NotRecoverable).state,
            )
            assertEquals(
                "a refused repair leaves the session alone",
                TapToPaySessionState.PendingActivation,
                fixture.state,
            )
        }
}
