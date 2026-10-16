package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.taptopay.enrollment.RouteScript
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A terminal whose session was replaced or ended refuses setup with 1019, and arms nothing.
 *
 * The reader is armed with the paypoint's own processor credentials and a charge reads on the last arming, so a
 * retired terminal re-arming it would make the next charge read under another merchant.
 */
class RetiredTerminalTest {
    private fun assertRetired(failure: Throwable?) {
        assertTrue(failure.toString(), failure is PayabliException)
        assertEquals(PayabliErrorType.SESSION_NOT_INITIALIZED, (failure as PayabliException).type)
        assertEquals("this terminal belongs to a session that was replaced or ended", failure.reason)
    }

    @Test
    fun `a retired terminal refuses initialize and sends and arms nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = SessionFixture(SessionFixture.coldScript(), isLive = { false })

            assertRetired(runCatching { fixture.coordinator.initialize() }.exceptionOrNull())

            assertEquals(emptyList<String>(), fixture.routes)
            assertEquals(0, fixture.reader.configureCount)
            assertEquals(0, fixture.reader.prepareCount)
        }

    @Test
    fun `a retired terminal refuses a repair and leaves its reader alone`() =
        runTest(timeout = TEST_TIMEOUT) {
            var live = true
            val fixture =
                SessionFixture(
                    SessionFixture.coldScript(),
                    isLive = { live },
                ).also { it.coordinator.initialize() }
            fixture.manager.invalidate()
            val sent = fixture.routes.toList()
            live = false

            assertRetired(runCatching { fixture.coordinator.reinitializeIfNeeded() }.exceptionOrNull())

            assertEquals(sent, fixture.routes)
            assertEquals(1, fixture.reader.configureCount)
            assertEquals(1, fixture.reader.prepareCount)
        }

    @Test
    fun `a retired terminal refuses an activation and sends nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            var live = true
            val fixture =
                SessionFixture(
                    SessionFixture.coldScript(),
                    isLive = { live },
                ).also { it.coordinator.initialize() }
            live = false

            assertRetired(runCatching { fixture.coordinator.activateDevice("123456") }.exceptionOrNull())

            assertFalse(RouteScript.ACTIVATE in fixture.routes)
            assertEquals("an activation moves nothing on a refusal", TapToPaySessionState.Ready, fixture.state)
        }

    @Test
    fun `a session replaced while a terminal attests is caught before the reader is armed`() =
        runTest(timeout = TEST_TIMEOUT) {
            var live = true
            val fixture =
                SessionFixture(SessionFixture.coldScript(), firstReadGate = { live = false }, isLive = { live })

            assertRetired(runCatching { fixture.coordinator.initialize() }.exceptionOrNull())

            assertTrue("the replacement landed during setup", RouteScript.CHALLENGE in fixture.routes)
            assertEquals(0, fixture.reader.configureCount)
            assertEquals(TapToPaySessionState.Idle, fixture.state)
        }
}
