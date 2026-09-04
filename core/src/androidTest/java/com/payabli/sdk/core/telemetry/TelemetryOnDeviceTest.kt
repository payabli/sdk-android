package com.payabli.sdk.core.telemetry

import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.device.platform.DeviceProfileFactory
import com.payabli.sdk.testutils.network.LoopbackServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reporting on real Android, which is the half a JVM test cannot reach.
 *
 * Every unit test here runs with no host bindings, so `TelemetryModule` takes its no-application branch and
 * the background flush is never registered. On a device the branch that runs is the other one: a real
 * `Context` resolved to a real `Application`, an observer accepted by `ProcessLifecycleOwner` on the main
 * thread, the flush coroutine on a real dispatcher, and the request over the device's own network stack.
 *
 * **No backend is involved.** The session points at a `LoopbackServer` on the device, so this proves the
 * client's path end to end without the endpoint existing anywhere. Nothing here needs the service deployed,
 * which is why it did not have to wait for one.
 *
 * What it still does not cover is the background *trigger* — that a real activity going away fires the
 * flush — because there is no activity in this test application to stop. That belongs with the sample app.
 */
class TelemetryOnDeviceTest {
    private val server = LoopbackServer()

    @After
    fun tearDown() {
        runBlocking { PayabliSession.reset() }
        TelemetryBootstraps.forget()
        server.close()
    }

    @Test
    fun theSessionStartsReportingAndShipsABatchFromTheDevice() {
        server.respondWith(202, "{}")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val host = HostBindings(context)

        val session =
            runBlocking {
                PayabliSession
                    .initializeAgainst(
                        server.baseUrl,
                        PayabliConfig(
                            entryPoint = ENTRY,
                            environment = PayabliEnvironment.SANDBOX,
                            tokenProvider = { "a-device-token" },
                        ),
                        host,
                    ).getOrThrow()
            }

        // Nothing called a start method. `sdk.initialized` is already queued by the module if it was found,
        // so this tops the batch up to the flush threshold.
        repeat(BATCH_SIZE) {
            TelemetryRecorders.record(TelemetryEvents.PAYIN_CAPTURE_COMPLETED) {
                mapOf(
                    TelemetryProperty.OUTCOME.key to TelemetryProperties.Outcome.APPROVED,
                    TelemetryProperty.DURATION_MS.key to "12",
                )
            }
        }

        val request = server.awaitOnlyRequestOrNull()

        assertNotNull("the SDK sent nothing from the device", request)
        assertEquals("/api/v2/telemetry/sdk", request!!.path)
        assertEquals("Bearer a-device-token", request.header("Authorization"))

        val body = request.body
        assertTrue("the batch was not attributed to the session's entry", body.contains("\"entry\":\"$ENTRY\""))
        assertTrue("schemaVersion is not the string form", body.contains("\"schemaVersion\":\"1\""))
        assertTrue("the session's own id is not on the wire", body.contains(session.telemetry.sessionId))
        // The fixed device facts, which only a device can answer: on a JVM `Build` throws rather than
        // returning, so every one of these is blank in a unit test and this is the only tier that sees them.
        val device = DeviceProfileFactory.of(context)
        mapOf(
            "deviceIdHash" to device.idHash,
            "deviceType" to device.type,
            "deviceOs" to device.os,
            "osVersion" to device.osVersion,
            "modelName" to device.modelName,
            "packageName" to device.packageName,
        ).forEach { (field, value) ->
            if (value.isBlank()) {
                assertFalse("$field was sent blank rather than omitted: $body", body.contains(field))
            } else {
                assertTrue("$field is not on the wire as \"$value\": $body", body.contains("\"$field\":\"$value\""))
            }
        }

        assertEquals("Android", device.os)
        assertTrue("the platform reported no model", device.modelName.isNotBlank())
        assertTrue("the platform reported no release", device.osVersion.isNotBlank())
        assertEquals(context.packageName, device.packageName)

        // No device type, because this test application takes no card-present payments and so can hold no
        // device record. That is the negative case on real hardware; the positive one cannot be shown from
        // here, since nothing in this repository puts card-present and reporting on one classpath. What
        // covers it is the pair `CardPresentModuleDiscoveryTest` and the uploader's own wire assertion.
        assertEquals("", device.type)
    }

    private companion object {
        const val ENTRY = "a-device-entrypoint"
        const val BATCH_SIZE = 20
    }
}
