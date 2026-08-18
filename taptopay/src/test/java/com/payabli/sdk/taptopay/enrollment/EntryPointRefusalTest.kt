package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.taptopay.attestation.device.DeviceServiceException
import com.payabli.sdk.taptopay.attestation.device.EntryPointFailures
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.CONFIGURATION_REJECTED
import com.payabli.sdk.taptopay.session.TapToPaySessionFailures
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * Each cold-sequence route carries the entry-point mapper, and the refusal reaches a host as a rejected
 * configuration.
 *
 * The mapper is a default argument, so a route that stops passing it still compiles and still passes every
 * test about the route itself. Only calling each one with the refusal shows the argument is there, which is
 * why this is one test per route rather than one for the sequence.
 */
class EntryPointRefusalTest {
    private fun refusal() = decline(403, EntryPointFailures.ENTRY_POINT_UNUSABLE)

    private suspend fun enrollAgainst(script: RouteScript): Throwable? {
        val fixture = EnrollmentFixture(script)
        return runCatching { fixture.enrollment.enroll() }.exceptionOrNull()
    }

    @Test
    fun `challenge refusing the entry point reaches the caller as an unusable entry point`() =
        runTest(timeout = TEST_TIMEOUT) {
            val thrown = enrollAgainst(RouteScript(RouteScript.CHALLENGE to listOf(refusal())))

            assertTrue("$thrown", thrown is DeviceServiceException.EntryPointUnusable)
        }

    @Test
    fun `register refusing the entry point reaches the caller as an unusable entry point`() =
        runTest(timeout = TEST_TIMEOUT) {
            val thrown =
                enrollAgainst(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(refusal()),
                    ),
                )

            assertTrue("$thrown", thrown is DeviceServiceException.EntryPointUnusable)
        }

    @Test
    fun `attest refusing the entry point reaches the caller as an unusable entry point`() =
        runTest(timeout = TEST_TIMEOUT) {
            val thrown =
                enrollAgainst(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody()),
                        RouteScript.ATTEST to listOf(refusal()),
                    ),
                )

            assertTrue("$thrown", thrown is DeviceServiceException.EntryPointUnusable)
        }

    @Test
    fun `activate refusing the entry point keeps the record and asks the host to fix its configuration`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(RouteScript(RouteScript.ACTIVATE to listOf(refusal())))
            fixture.seedRecord()

            val thrown =
                runCatching { fixture.enrollment.confirmActivation(ACTIVATION_CODE) }.exceptionOrNull()

            assertTrue("$thrown", thrown is DeviceActivationException.EntryPointUnusable)
            assertNotNull("a configuration fault discards nothing", fixture.storedRecord())
        }

    @Test
    fun `both classifications land a session on a rejected configuration`() {
        // The two families reach the state through different landings, and a host reads only the state.
        assertEquals(
            TapToPaySessionState.Failed(CONFIGURATION_REJECTED),
            TapToPaySessionFailures.landingFor(
                DeviceServiceException.EntryPointUnusable(403, EntryPointFailures.ENTRY_POINT_UNUSABLE),
            ),
        )
        assertEquals(
            TapToPaySessionState.Failed(CONFIGURATION_REJECTED),
            TapToPaySessionFailures.landingFor(
                DeviceActivationException.EntryPointUnusable(403, EntryPointFailures.ENTRY_POINT_UNUSABLE),
            ),
        )
    }
}
