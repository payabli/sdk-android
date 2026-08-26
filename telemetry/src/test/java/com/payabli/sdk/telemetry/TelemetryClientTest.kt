@file:OptIn(ExperimentalCoroutinesApi::class)

package com.payabli.sdk.telemetry

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.telemetry.TelemetryDeviceContext
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetrySessionContext
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Two things about the harness, both measured rather than preferred, and both easy to get wrong again.
 *
 * The client's scope is built on `UnconfinedTestDispatcher` and not on `runTest`'s `backgroundScope`. With the
 * background scope, a coroutine launched by `record` stayed `Active` and never ran, through `advanceUntilIdle`
 * and through `testScheduler.advanceUntilIdle` both, so a flush that works in production read as a flush that
 * never happens. Unconfined runs the launch eagerly, and `delay` still goes through the scheduler, so virtual
 * time drives the timer exactly as before.
 *
 * Every client is stopped, which is what [withClient] is for. The timer is a `while (isActive)` loop around a
 * `delay`, so a scope left running keeps the scheduler busy forever and the whole suite hangs at teardown
 * rather than failing.
 */
class TelemetryClientTest {
    private val logger = RecordingSdkLogger()

    private val context =
        TelemetrySessionContext(
            entryPoint = "an-entry-point",
            environment = PayabliEnvironment.SANDBOX,
            telemetryEnabled = true,
            sessionId = "0f8d2a1c-4b6e-4a2f-9c3d-5e7f8a9b0c1d",
            device =
                TelemetryDeviceContext(
                    idHash = DEVICE,
                    type = "Softpos",
                    os = "Android",
                    osVersion = "14",
                    modelName = "Pixel 7a",
                ),
        )

    @Test
    fun afullBatchIsSentWithoutWaitingForTheTimer() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()

            withClient(transport, batchSize = 3) { client ->
                repeat(3) { record(client) }

                assertEquals(1, transport.sent.size)
            }
        }

    @Test
    fun ashortOfAFullBatchNothingIsSentUntilTheTimerFires() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()

            withClient(transport, batchSize = 3) { client ->
                client.start()
                repeat(2) { record(client) }

                advanceTimeBy(FLUSH_INTERVAL - 1.seconds)
                assertTrue(transport.sent.isEmpty())

                advanceTimeBy(2.seconds)
                assertEquals(1, transport.sent.size)
            }
        }

    @Test
    fun stoppingSendsWhatIsStillQueued() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()

            withClient(transport, batchSize = 10) { client ->
                client.start()
                record(client)

                client.stop()

                assertEquals(1, transport.sent.size)
            }
        }

    /**
     * The gate, at the moment it has to happen.
     *
     * Scrubbing on the way out would leave the value in memory for as long as the batch waited, so what is
     * asserted is that the body never carries it, from a queue that never held it.
     */
    @Test
    fun akeyTheEventDoesNotDeclareNeverReachesTheWire() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()

            withClient(transport, batchSize = 1) { client ->
                client.record(
                    TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                    mapOf(
                        TelemetryProperties.OUTCOME to "approved",
                        "cardNumber" to "4111111111111111",
                    ),
                )

                val body = transport.bodyAsText()
                assertFalse(body.contains("4111111111111111"))
                assertFalse(body.contains("cardNumber"))
                assertTrue(body.contains(""""properties":{"outcome":"approved"}"""))
            }
        }

    @Test
    fun aneventOutsideTheCatalogIsNotQueuedAtAll() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()

            withClient(transport, batchSize = 1) { client ->
                client.record("payin.capture.invented", emptyMap())

                assertTrue(transport.sent.isEmpty())
            }
        }

    @Test
    fun oneRequestCarriesNoMoreThanOneRequestMay() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()

            withClient(transport, batchSize = 100, maxEventsPerRequest = 2) { client ->
                repeat(5) { record(client) }

                client.flush()

                assertEquals(1, transport.sent.size)
                assertEquals(2, transport.bodyAsText().split("\"schemaVersion\"").size - 1)
            }
        }

    private suspend fun TestScope.withClient(
        transport: FakeTransport,
        batchSize: Int,
        maxEventsPerRequest: Int = 100,
        body: suspend (TelemetryClient) -> Unit,
    ) {
        val client =
            TelemetryClient(
                queue = TelemetryQueue(capacity = 500),
                uploader = TelemetryUploader(transport, context, logger),
                scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                flushInterval = FLUSH_INTERVAL,
                batchSize = batchSize,
                maxEventsPerRequest = maxEventsPerRequest,
                logger = logger,
                now = { FIXED_NOW },
            )
        try {
            body(client)
        } finally {
            client.stop()
        }
    }

    private fun record(client: TelemetryClient) {
        client.record(TelemetryEvents.SDK_INITIALIZED, mapOf(TelemetryProperties.STATE to "ready"))
    }

    private companion object {
        val TEST_TIMEOUT = 10.seconds
        val FLUSH_INTERVAL = 30.seconds
        const val DEVICE = "9f2c4b7e1a05d38c6e4b90f7c2a1d5e3"
        const val FIXED_NOW = 1_755_000_000_000
    }
}
