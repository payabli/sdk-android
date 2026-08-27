package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What an activation incident asks of these five routes: how long each took, how it ended, and which code the
 * far side gave. A per-call log record answers none of that, which is why this exists as well as those.
 */
class DeviceRouteTelemetryTest {
    private val logger = RecordingSdkLogger()
    private val recorded = mutableListOf<Pair<String, Map<String, String>>>()

    @Before
    fun install() {
        TelemetryRecorders.install { event, properties -> recorded += event to properties }
    }

    @After
    fun clear() {
        TelemetryRecorders.clear()
    }

    @Test
    fun `a route that answers is reported as succeeded, under its own name`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport =
                FakeDeviceTransport.answering(successEnvelope("""{"challengeId":"c-1","challenge":"Y2g="}"""))

            DeviceServiceClient(transport, logger).challenge(ENTRY_POINT)

            val (event, properties) = recorded.single()
            assertEquals(TelemetryEvents.TTP_DEVICE_CHALLENGE_COMPLETED, event)
            assertEquals(TelemetryProperties.Outcome.SUCCEEDED, properties[TelemetryProperty.OUTCOME.key])
            assertNotNull(properties[TelemetryProperty.DURATION_MS.key]?.toLongOrNull())
            assertNull(properties[TelemetryProperty.CODE.key])
        }

    /** Refused means the far side answered and said no, which on these routes arrives inside a 200. */
    @Test
    fun `a declined envelope is reported as refused, carrying the code and not the text`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeDeviceTransport.answering(declineEnvelope(400, "the entry point echoed back"))

            runCatching {
                DeviceServiceClient(transport, logger).register(
                    entry = ENTRY_POINT,
                    hardwareId = "hardware-id-value",
                    keyId = "key-id-value",
                    deviceName = null,
                    model = null,
                    osVersion = null,
                )
            }

            val (event, properties) = recorded.single()
            assertEquals(TelemetryEvents.TTP_DEVICE_REGISTER_COMPLETED, event)
            assertEquals(TelemetryProperties.Outcome.REFUSED, properties[TelemetryProperty.OUTCOME.key])
            assertEquals("400", properties[TelemetryProperty.CODE.key])
            assertTrue(properties.values.none { it.contains("echoed") })
        }

    @Test
    fun `a response that cannot be read is reported as failed rather than refused`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeDeviceTransport.answering(successEnvelope("""{"not":"a challenge"}"""))

            runCatching { DeviceServiceClient(transport, logger).challenge(ENTRY_POINT) }

            assertEquals(
                TelemetryProperties.Outcome.FAILED,
                recorded.single().second[TelemetryProperty.OUTCOME.key],
            )
        }

    private companion object {
        val TEST_TIMEOUT = 5.seconds
        const val ENTRY_POINT = "a-test-entrypoint"
    }
}
