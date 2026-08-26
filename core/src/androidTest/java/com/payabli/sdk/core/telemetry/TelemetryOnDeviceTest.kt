package com.payabli.sdk.core.telemetry

import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.device.platform.DeviceIdentifierFactory
import com.payabli.sdk.testutils.network.LoopbackServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reporting on real Android, which is the half a JVM test cannot reach.
 *
 * Every unit test here runs with no host bindings, so `TelemetryModule` takes its no-application branch and
 * the background flush is never registered. On a device the branch that runs is the other one: a real
 * `Context` resolved to a real `Application`, `registerActivityLifecycleCallbacks` accepted by the framework,
 * the flush coroutine on a real dispatcher, and the request over the device's own network stack.
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
                            accessToken = "a-device-token",
                            entryPoint = ENTRY,
                            environment = PayabliEnvironment.QA,
                        ),
                        host,
                    ).getOrThrow()
            }

        // Nothing called a start method. `sdk.initialized` is already queued by the module if it was found,
        // so this tops the batch up to the flush threshold.
        repeat(BATCH_SIZE) {
            TelemetryRecorders.record(TelemetryEvents.PAYIN_CAPTURE_COMPLETED) {
                mapOf(
                    TelemetryProperties.OUTCOME to TelemetryProperties.Outcome.APPROVED,
                    TelemetryProperties.DURATION_MS to "12",
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
        // Which device the run happened on, derived from the platform value only a device has.
        assertTrue(
            "the device is not identified on the wire",
            body.contains("\"deviceIdHash\":\"${DeviceIdentifierFactory.of(context)}\""),
        )
    }

    private companion object {
        const val ENTRY = "a-device-entrypoint"
        const val BATCH_SIZE = 20
    }
}
