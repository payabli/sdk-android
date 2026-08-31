package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.taptopay.attestation.AttestationToken
import com.payabli.sdk.taptopay.enrollment.DeviceActivationException
import com.payabli.sdk.taptopay.enrollment.DeviceActivationFailures
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

    /**
     * The other two routes, so every one of the five is pinned to its own name.
     *
     * The mapping is a table keyed on the route template, and three of its rows were asserted. Swapping the
     * two that were not would have put attestation and configuration into another route's series with the
     * suite green, which is a rename nobody would see until a chart had already moved.
     */
    @Test
    fun `attesting is reported under its own name`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport =
                FakeDeviceTransport.answering(successEnvelope("""{"registered":true,"isSandbox":false}"""))

            DeviceServiceClient(transport, logger).attest(
                entry = ENTRY_POINT,
                challengeId = "c-1",
                identity = DeviceIdentity(deviceId = "a-device-id", keyId = "key-id-value", publicKey = "a-key"),
                appId = "an-app-id",
                token = AttestationToken("a-token"),
            )

            assertEquals(TelemetryEvents.TTP_DEVICE_ATTEST_COMPLETED, recorded.single().first)
        }

    @Test
    fun `configuring is reported under its own name`() =
        runTest(timeout = TEST_TIMEOUT) {
            val credentials =
                """{"platform":"a-platform","secretKey":"s","apiKey":"a","merchantId":"m",""" +
                    """"environment":"sandbox","currencyCode":"USD","merchantName":"A Merchant",""" +
                    """"merchantCategoryCode":"5999","terminalId":"t-1","ppId":"pp-1","hostPort":"h:1"}"""
            val transport = FakeDeviceTransport.answering(successEnvelope("""{"credentials":$credentials}"""))

            DeviceServiceClient(transport, logger).config(
                entry = ENTRY_POINT,
                assertion =
                    DeviceAssertion(
                        assertion = "an-assertion",
                        keyId = "key-id-value",
                        deviceId = "a-device-id",
                        timestamp = "2026-08-27T00:00:00Z",
                    ),
            )

            assertEquals(TelemetryEvents.TTP_DEVICE_CONFIG_COMPLETED, recorded.single().first)
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

    /**
     * A refusal the caller mapped to its own type is still a refusal.
     *
     * `/activate` is the only route whose caller hands in a mapper producing something outside the two types
     * the measurement catches: `DeviceActivationException` extends `Exception` directly, where every other
     * mapper returns a `DeviceServiceException`. So the one route that can refuse a payer's code was the one
     * route reporting nothing when it did.
     */
    @Test
    fun `an activation refusal mapped by its caller is still reported`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeDeviceTransport.answering(declineEnvelope(400, "Invalid activation code"))

            val raised =
                runCatching {
                    DeviceServiceClient(transport, logger).activate(
                        entry = ENTRY_POINT,
                        deviceId = "a-device-id",
                        activationCode = "000000",
                        assertion =
                            DeviceAssertion(
                                assertion = "an-assertion",
                                keyId = "key-id-value",
                                deviceId = "a-device-id",
                                timestamp = "2026-08-27T00:00:00Z",
                            ),
                        failureMapper = DeviceActivationFailures(logger),
                    )
                }.exceptionOrNull()

            assertTrue("the caller's mapper did not run: $raised", raised is DeviceActivationException)
            val (event, properties) = recorded.single()
            assertEquals(TelemetryEvents.TTP_DEVICE_ACTIVATE_COMPLETED, event)
            assertEquals(TelemetryProperties.Outcome.REFUSED, properties[TelemetryProperty.OUTCOME.key])
            assertEquals("400", properties[TelemetryProperty.CODE.key])
        }

    /**
     * A 500 out of that route is the service breaking, not the payer being refused.
     *
     * The caller's mapper turns any code at or above 500 into `ServiceFailed`, and the other four routes call
     * the equivalent `failed`. Reporting activation's as `refused` put a service outage into the decline rate
     * of the one route whose decline rate is read.
     */
    @Test
    fun `an activation that the service failed to answer is reported as failed`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeDeviceTransport.answering(declineEnvelope(500, "Internal error"))

            val raised =
                runCatching {
                    DeviceServiceClient(transport, logger).activate(
                        entry = ENTRY_POINT,
                        deviceId = "a-device-id",
                        activationCode = "000000",
                        assertion =
                            DeviceAssertion(
                                assertion = "an-assertion",
                                keyId = "key-id-value",
                                deviceId = "a-device-id",
                                timestamp = "2026-08-27T00:00:00Z",
                            ),
                        failureMapper = DeviceActivationFailures(logger),
                    )
                }.exceptionOrNull()

            assertTrue("the mapper did not classify it: $raised", raised is DeviceActivationException.ServiceFailed)
            val properties = recorded.single().second
            assertEquals(TelemetryProperties.Outcome.FAILED, properties[TelemetryProperty.OUTCOME.key])
        }

    private companion object {
        val TEST_TIMEOUT = 5.seconds
        const val ENTRY_POINT = "a-test-entrypoint"
    }
}
