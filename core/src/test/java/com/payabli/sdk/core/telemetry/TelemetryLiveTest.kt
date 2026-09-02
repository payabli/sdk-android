package com.payabli.sdk.core.telemetry

import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The SDK's own request path, end to end, against a running service.
 *
 * Everything else about this module is proven against a fake transport or by posting a body by hand. Neither
 * shows what an integrator gets, which is: a session is initialized, the reporting module is discovered and
 * started without anything being called, events are recorded through the seam a capability uses, a full batch
 * triggers a flush, and the request is assembled and sent by the same transport every other route uses.
 *
 * **Driven by filling a batch rather than by reaching for a flush.** The flush entry points belong to the
 * telemetry module and are not visible here, which is correct — and it means the trigger under test is the
 * one a real app hits rather than a door opened for the test.
 *
 * Skipped unless the [RECORD] environment variable names a writable path, so an ordinary run and CI
 * are unaffected. The endpoint it records against is stood up outside this repository, and nothing here
 * invents a value for it.
 */
class TelemetryLiveTest {
    @After
    fun restoreProcessWideState() {
        runBlocking { PayabliSession.reset() }
        TelemetryBootstraps.forget()
    }

    @Test
    fun theSdkAssemblesAndSendsABatchThroughItsOwnTransport() =
        runTest {
            // Required rather than assumed: this class is excluded by name when the variable is absent, so
            // reaching here without it is a broken filter and has to say so instead of reporting a skip.
            val configured = requireNotNull(System.getenv(RECORD)) { "$RECORD is not set" }
            val record = File(configured)
            require(record.parentFile?.isDirectory == true) { "$RECORD names no existing directory" }
            val baseUrl = System.getenv(BASE_URL) ?: "http://127.0.0.1:4099"
            record.delete()

            val session =
                PayabliSession
                    .initializeAgainst(
                        baseUrl,
                        PayabliConfig(
                            accessToken = "a-local-token",
                            entryPoint = ENTRY,
                            environment = PayabliEnvironment.SANDBOX,
                        ),
                    ).getOrThrow()

            // Nothing called a start method. If the module was not found, no recorder is installed, nothing
            // is ever queued and the wait below times out — so the request arriving at all is the assertion
            // that auto-discovery ran.
            // One full batch. `sdk.initialized` is already queued by the module, so this tops it up.
            repeat(BATCH_SIZE) {
                TelemetryRecorders.record(TelemetryEvents.PAYIN_CAPTURE_COMPLETED) {
                    mapOf(
                        TelemetryProperty.OUTCOME.key to TelemetryProperties.Outcome.APPROVED,
                        TelemetryProperty.DURATION_MS.key to "12",
                    )
                }
            }

            val sent = awaitRequest(record) ?: error("the SDK sent nothing within $TIMEOUT_MILLIS ms")

            assertTrue("wrong route: $sent", sent.contains(""""path": "/api/v2/telemetry/sdk""""))
            assertTrue("the bearer the session holds was not sent: $sent", sent.contains("Bearer a-local-token"))
            assertTrue("not the entry the session was configured with", sent.contains(""""entry\":\"$ENTRY\""""))
            assertTrue("schemaVersion is not the string form", sent.contains("""schemaVersion\":\"1\""""))
            // One per event, not one per request: every event carries the id of the session that produced it,
            // which is what makes a run readable as a sequence at the far end.
            val events = sent.split("schemaVersion").size - 1
            assertTrue("a full batch should have shipped, saw $events events", events >= BATCH_SIZE)
            assertEquals(
                "every event should carry the session's own id",
                events,
                sent.split(session.telemetry.sessionId).size - 1,
            )
        }

    /** Waits for the SDK to flush, since the flush is asynchronous and nothing reports it back. */
    private fun awaitRequest(record: File): String? {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (record.isFile && record.length() > 0) return record.readText()
            Thread.sleep(POLL_MILLIS)
        }
        return null
    }

    private companion object {
        const val RECORD = "PAYABLI_TELEMETRY_LIVE_RECORD"
        const val BASE_URL = "PAYABLI_TELEMETRY_LIVE_BASE_URL"
        const val ENTRY = "test21"
        const val BATCH_SIZE = 20
        const val TIMEOUT_MILLIS = 20_000L
        const val POLL_MILLIS = 200L
    }
}
