package com.payabli.sdk.taptopay.session

import com.payabli.sdk.taptopay.enrollment.RouteScript
import com.payabli.sdk.taptopay.enrollment.configBody
import com.payabli.sdk.taptopay.provider.DeviceIneligibleException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first question a session asks, and the only one whose answer no repair can change.
 *
 * A handset that cannot take contactless payments would fail somewhere further in regardless — at the
 * platform verdict, or at the reader — and every one of those places would report it as something else. So
 * it is asked first, and it is asked before anything is sent.
 */
class SessionEligibilityTest {
    private val ineligible = DeviceIneligibleException("contactless payments are not supported")

    @Test
    fun `an ineligible device fails the session and lands where no repair is offered`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = SessionFixture(SessionFixture.coldScript(), eligibilityFailure = ineligible)

            val failure = runCatching { fixture.coordinator.initialize() }.exceptionOrNull()

            assertTrue("$failure", failure is DeviceIneligibleException)
            assertEquals(TapToPaySessionState.Failed(TapToPayFailureReason.DEVICE_INELIGIBLE), fixture.state)
        }

    @Test
    fun `the handset is asked before anything is sent and before any key is touched`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = SessionFixture(SessionFixture.coldScript(), eligibilityFailure = ineligible)

            runCatching { fixture.coordinator.initialize() }

            assertEquals("nothing was sent", emptyList<String>(), fixture.routes)
            assertEquals("the platform was never asked for a verdict", 0, fixture.enrollment.attestor.challenges.size)
            assertEquals("the reader was never configured", 0, fixture.reader.configureCount)
        }

    @Test
    fun `the question is asked once per build, before the state moves`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = SessionFixture(SessionFixture.coldScript())

            fixture.coordinator.initialize()

            assertEquals(TapToPaySessionState.Ready, fixture.state)
            assertEquals(1, fixture.reader.eligibilityCount)
            assertEquals(
                "eligibility comes before the reader is given anything",
                listOf("reader:eligibility", "reader:configure", "reader:prepare"),
                fixture.enrollment.trace.filter { it.startsWith("reader:") },
            )
        }

    @Test
    fun `a repair does not ask again, because the hardware did not change`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(RouteScript(RouteScript.CONFIG to listOf(configBody(), configBody())))
            fixture.seedRecord()
            fixture.coordinator.initialize()
            fixture.manager.invalidate()

            fixture.coordinator.reinitializeIfNeeded()

            assertEquals(TapToPaySessionState.Ready, fixture.state)
            assertEquals(1, fixture.reader.eligibilityCount)
        }
}
