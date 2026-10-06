package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.taptopay.enrollment.DEVICE_ID
import com.payabli.sdk.taptopay.enrollment.FakeDeviceKey
import com.payabli.sdk.taptopay.enrollment.RouteScript
import com.payabli.sdk.taptopay.enrollment.attestBody
import com.payabli.sdk.taptopay.enrollment.challengeBody
import com.payabli.sdk.taptopay.enrollment.registerBody
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private suspend fun failureOf(block: suspend () -> Unit): Throwable? = runCatching { block() }.exceptionOrNull()

/** What `initialize` does with a terminal whose device key is gone, and with one whose key cannot be checked. */
class SessionDeviceKeyTest {
    @Test
    fun `a terminal whose key is gone recovers on initialize, keeps its id and owes a new code`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "pending")),
                        RouteScript.ATTEST to listOf(attestBody()),
                    ),
                    deviceKey = FakeDeviceKey(lost = true),
                )
            fixture.seedRecord()

            val failure = failureOf { fixture.coordinator.initialize() }

            assertTrue("$failure", failure is TapToPaySessionException.PendingActivation)
            assertEquals(TapToPaySessionState.PendingActivation, fixture.state)
            assertEquals(DEVICE_ID, fixture.coordinator.deviceId())
        }

    @Test
    fun `a terminal whose key cannot be checked keeps its binding and fails as the key being unavailable`() =
        runTest(timeout = TEST_TIMEOUT) {
            val key = FakeDeviceKey(publicKeyFailure = DeviceKeyException.CryptoUnavailable())
            val fixture = SessionFixture(RouteScript(), deviceKey = key)
            fixture.seedRecord()

            val failure = failureOf { fixture.coordinator.initialize() }

            assertTrue("$failure", failure is DeviceKeyException.CryptoUnavailable)
            assertEquals(
                TapToPaySessionState.Failed(TapToPayFailureReason.DEVICE_KEY_UNAVAILABLE),
                fixture.state,
            )
            assertEquals(0, key.provisions)
            assertTrue(fixture.routes.toString(), fixture.routes.isEmpty())
            assertEquals(DEVICE_ID, fixture.coordinator.deviceId())
        }
}
