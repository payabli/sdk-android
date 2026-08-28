@file:OptIn(ExperimentalCoroutinesApi::class)

package com.payabli.sdk.telemetry

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.telemetry.TelemetryDeviceContext
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetrySessionContext
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
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

    /**
     * Stopping sends everything, not one request's worth.
     *
     * A queue holds far more than one request carries, so a final flush that ran once left the rest to die
     * with the scope it was about to cancel. Nothing reported it: the events were accepted by `record`, and
     * the contract says queued events leave.
     */
    @Test
    fun stoppingSendsEverythingQueuedAndNotJustOneRequest() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()

            withClient(transport, batchSize = 1_000, maxEventsPerRequest = 10) { client ->
                client.start()
                repeat(45) { record(client) }

                client.stop()

                assertEquals("every queued event should have left", 45, transport.eventsSent())
                assertEquals("in five requests of at most ten each", 5, transport.sent.size)
            }
        }

    /**
     * A stalled upload must not grow the process by event volume.
     *
     * The queue is bounded; the coroutines draining it were not. Every event past the batch size launched
     * its own, and against a request that is not returning each one parks on the same lock, so a busy app
     * holds a job per event while the queue it is bounded by never moves.
     *
     * Counted on the scope rather than on the transport, because the transport cannot tell the two apart:
     * one call is in flight either way and the rest are parked behind it.
     */
    @Test
    fun aStalledUploadDoesNotAccumulateAJobPerEvent() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()
            transport.stall()
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            val client = clientOn(scope, transport, batchSize = 1)

            try {
                repeat(200) { record(client) }

                assertEquals(
                    "one flush should be in flight whatever the event count",
                    1,
                    scope.coroutineContext.job.children
                        .count(),
                )
            } finally {
                transport.release()
                scope.cancel()
            }
        }

    /**
     * A failure does not wait for nineteen more events.
     *
     * The whole point of the tier: a process that dies takes the queue, and it usually dies just after
     * something went wrong, so a batch that would have carried the failure is the batch least likely to leave.
     */
    @Test
    fun anEventReportingAFailureShipsWithoutFillingABatch() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()

            withClient(transport, batchSize = 20) { client ->
                client.record(
                    TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                    mapOf(TelemetryProperty.OUTCOME.key to TelemetryProperties.Outcome.DECLINED),
                )

                assertEquals("a declined payment should not have waited", 1, transport.sent.size)
            }
        }

    /** And a success does wait, or the tier would be a batch size of one. */
    @Test
    fun anEventReportingSuccessWaitsForItsBatch() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()

            withClient(transport, batchSize = 20) { client ->
                repeat(19) { record(client) }

                assertTrue("a batch left before it was full", transport.sent.isEmpty())
            }
        }

    /**
     * The forced flush carries what was already queued, which is the reason it substitutes for storage.
     *
     * Sending the failure alone would leave the run-up to it in the queue for the crash that follows.
     */
    @Test
    fun aForcedFlushTakesTheQueuedContextWithIt() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()

            withClient(transport, batchSize = 100) { client ->
                repeat(5) { record(client) }
                client.record(
                    TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                    mapOf(TelemetryProperty.OUTCOME.key to TelemetryProperties.Outcome.FAILED),
                )

                assertEquals("one request, not one per event", 1, transport.sent.size)
                assertEquals("the context should have left with the failure", 6, transport.eventsSent())
            }
        }

    /**
     * A flush asked for while one is running is taken, not dropped.
     *
     * Coalescing alone would discard it, and for a full batch that costs nothing because the running flush
     * drains the same queue. For a failure it costs everything: the drain may already have happened, and the
     * event would then wait out the timer it was trying to skip.
     */
    @Test
    fun aFailureArrivingDuringAFlushIsNotLeftBehind() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()
            transport.stall()

            withClient(transport, batchSize = 100) { client ->
                // One event, and a flush that reaches the transport and parks there holding it.
                record(client)
                client.flushAsync()

                // Queued behind a drain that has already happened. Nothing else will send this: the batch is
                // nowhere near full and the timer is 30 seconds away.
                client.record(
                    TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                    mapOf(TelemetryProperty.OUTCOME.key to TelemetryProperties.Outcome.FAILED),
                )

                transport.release()

                // Nothing flushes here. A rescue flush would satisfy the assertion whether or not the
                // re-arm exists, which makes it a test of nothing.
                assertEquals("the re-armed flush never ran", 2, transport.sent.size)
                assertTrue(
                    "the failure never reached the wire",
                    transport.bodiesAsText().any { it.contains(TelemetryProperties.Outcome.FAILED) },
                )
            }
        }

    /**
     * What the queue evicted rides the next batch that leaves.
     *
     * A log line reaches the device and nothing else, so a stream that went quiet and a stream that
     * overflowed read the same at the far end, which is the one question this count exists to answer. It
     * travels on a request that is succeeding rather than in one of its own: a report of lost telemetry sent
     * as telemetry would be queued by the queue that just overflowed.
     */
    @Test
    fun whatTheQueueEvictedIsCarriedByTheNextBatch() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()

            withClient(transport, batchSize = 1_000, maxEventsPerRequest = 1_000, capacity = 4) { client ->
                repeat(10) { record(client) }

                client.flush()

                assertTrue(
                    "the count did not reach the wire: ${transport.bodyAsText()}",
                    transport.bodyAsText().contains(""""droppedSinceLastSend":6"""),
                )
            }
        }

    /** And a batch that lost nothing says nothing, rather than sending a zero on every request. */
    @Test
    fun aBatchThatLostNothingCarriesNoCount() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()

            withClient(transport, batchSize = 2) { client ->
                repeat(2) { record(client) }

                assertTrue(transport.bodyAsText(), !transport.bodyAsText().contains("dropped"))
            }
        }

    /**
     * Shutting down while the timer's upload is in flight does not lose that batch.
     *
     * A timer that uploads on its own coroutine is cancelled by [TelemetryClient.stop] mid-send, and by then
     * the events are drained and the drop count taken: neither is anywhere the shutdown drain can find, since
     * the queue is empty and the counter reads zero.
     */
    @Test
    fun stoppingDuringTheTimersUploadKeepsTheBatch() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()
            val client =
                clientOn(
                    scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                    transport = transport,
                    batchSize = 1_000,
                )
            client.start()
            record(client)
            transport.stall()

            // The timer fires and the upload parks inside the transport.
            advanceTimeBy(FLUSH_INTERVAL + 1.seconds)
            assertEquals("the timer never reached the transport", 1, transport.sent.size)

            client.stop()
            transport.release()

            // `sent` alone cannot answer this: the fake records a request before it parks, so a cancelled
            // send counts there either way. What separates them is whether it got past the gate.
            assertEquals("the batch the timer was sending was lost", 1, transport.completed.size)
        }

    /**
     * A record keeps the session it was made under, whichever channel ends up sending it.
     *
     * An operation that starts under one session and answers after a re-initialize is recorded on the
     * successor's channel, since that is what is installed by then.
     */
    @Test
    fun aRecordCarriesTheSessionItWasMadeUnder() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()
            val earlier = aSession(sessionId = "the-retired-session")

            withClient(transport, batchSize = 1) { client ->
                client.record(
                    TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                    mapOf(TelemetryProperty.OUTCOME.key to TelemetryProperties.Outcome.APPROVED),
                    earlier,
                )

                assertTrue(
                    "the sending channel stamped its own session: ${transport.bodyAsText()}",
                    transport.bodyAsText().contains(""""sessionId":"the-retired-session""""),
                )
            }
        }

    /**
     * A padded entry point is the same entry point.
     *
     * `PayabliConfig` accepts anything non-blank, and every request writer trims before sending, so a
     * configuration of `" an-entry-point "` reaches the service as `an-entry-point`. Compared untrimmed, the
     * check below reads a flow using the trimmed form as a foreign entry point and drops all of its events,
     * and what does reach the wire is attributed under a spelling the service never recorded.
     */
    @Test
    fun aPaddedEntryPointIsNotAForeignOne() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()
            val padded = aSession(entryPoint = "  an-entry-point  ", sessionId = "the-padded-session")

            withClient(transport, batchSize = 1) { client ->
                client.record(
                    TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                    mapOf(TelemetryProperty.OUTCOME.key to TelemetryProperties.Outcome.APPROVED),
                    padded,
                )

                assertEquals("the padded session read as another entry point", 1, transport.eventsSent())
                assertTrue(
                    "the padding reached the wire: ${transport.bodyAsText()}",
                    transport.bodyAsText().contains(""""entry":"an-entry-point""""),
                )
            }
        }

    /**
     * A record from another entry point is dropped rather than rewritten.
     *
     * The batch's entry is what the request is authorized against and the service drops an event whose own
     * entry differs, so this record cannot be delivered truthfully on this channel at any price. Rewriting it
     * to the sending session's entry is what filed one merchant's work under another.
     */
    @Test
    fun aRecordFromAnotherEntryPointIsNotSentUnderThisOne() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()
            val elsewhere = aSession(entryPoint = "another-entry-point", sessionId = "a-session-elsewhere")

            withClient(transport, batchSize = 1) { client ->
                client.record(
                    TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
                    mapOf(TelemetryProperty.OUTCOME.key to TelemetryProperties.Outcome.APPROVED),
                    elsewhere,
                )

                assertEquals("a foreign record was sent: ${transport.bodiesAsText()}", 0, transport.eventsSent())
            }
        }

    /**
     * One signal sends what was waiting when it was taken, not one request's worth of it.
     *
     * The channel conflates, so every request made while an upload is parked collapses into one. Answering it
     * with a single batch left the rest of a burst waiting for the timer, including anything that had asked
     * to leave at once, which is the whole of what the force-send tier promises.
     */
    @Test
    fun oneSignalDrainsWhatTheStallHadQueued() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeTransport()
            transport.stall()

            withClient(transport, batchSize = 100, maxEventsPerRequest = 100, capacity = 500) { client ->
                // One event, and a flush that reaches the transport and parks there holding it.
                record(client)
                client.flushAsync()

                // Two and a half requests' worth arrive behind it, and every request they ask for is one
                // pending signal by the time anything reads it.
                repeat(250) { record(client) }

                transport.release()

                // Nothing flushes here. A rescue would pass whether or not the signal covers more than a
                // single batch, which is the thing under test.
                assertEquals("the burst was left for the timer", 251, transport.eventsSent())
            }
        }

    /**
     * A refused batch does not take the count with it.
     *
     * A refused batch's events are not retried, and the uploader swallows the refusal, so a count taken
     * with them is a count nobody sees: the batch that finally lands reports none, and a stream that
     * overflowed while offline arrives looking quiet.
     */
    @Test
    fun aCountSurvivesTheBatchThatWasRefused() =
        runTest(timeout = TEST_TIMEOUT) {
            var refuse = true
            val transport =
                FakeTransport {
                    if (refuse) {
                        PayabliResponse(statusCode = 500, headers = emptyMap(), body = ByteArray(0))
                    } else {
                        FakeTransport.accepted()
                    }
                }

            withClient(transport, batchSize = 1_000, maxEventsPerRequest = 1_000, capacity = 4) { client ->
                repeat(10) { record(client) }
                client.flush()

                refuse = false
                record(client)
                client.flush()

                assertTrue(
                    "the refused batch took the count with it: ${transport.bodyAsText(1)}",
                    transport.bodyAsText(1).contains(""""droppedSinceLastSend":6"""),
                )
            }
        }

    /**
     * A count read with nothing to send is a count nobody ever sees.
     *
     * `takeDropCount` resets as it reads, so taking it before the batch was checked consumed the evictions
     * and discarded them: the next real batch then reported none.
     */
    @Test
    fun aFlushWithNothingToSendDoesNotConsumeTheCount() =
        runTest(timeout = TEST_TIMEOUT) {
            var refuse = true
            val transport =
                FakeTransport {
                    if (refuse) {
                        PayabliResponse(statusCode = 500, headers = emptyMap(), body = ByteArray(0))
                    } else {
                        FakeTransport.accepted()
                    }
                }

            withClient(transport, batchSize = 1_000, maxEventsPerRequest = 1_000, capacity = 4) { client ->
                // Refused, so the count goes back to the queue and is still owed while nothing is queued.
                // Reported instead, the counter would be zero here and this would pass against the defect.
                repeat(10) { record(client) }
                client.flush()
                transport.sent.clear()
                refuse = false

                // Nothing queued, so this sends nothing and must take nothing.
                client.flush()

                record(client)
                client.flush()
                assertTrue(
                    "the empty flush consumed what was still owed: ${transport.bodyAsText()}",
                    transport.bodyAsText().contains(""""droppedSinceLastSend":6"""),
                )
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
                        TelemetryProperty.OUTCOME.key to "approved",
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
        capacity: Int = 500,
        body: suspend (TelemetryClient) -> Unit,
    ) {
        val client =
            clientOn(
                scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
                transport = transport,
                batchSize = batchSize,
                maxEventsPerRequest = maxEventsPerRequest,
                capacity = capacity,
            )
        try {
            body(client)
        } finally {
            client.stop()
        }
    }

    private fun clientOn(
        scope: CoroutineScope,
        transport: FakeTransport,
        batchSize: Int,
        maxEventsPerRequest: Int = 100,
        capacity: Int = 500,
    ) = TelemetryClient(
        context = context,
        queue = TelemetryQueue(capacity = capacity),
        uploader = TelemetryUploader(transport, context, logger),
        scope = scope,
        flushInterval = FLUSH_INTERVAL,
        batchSize = batchSize,
        maxEventsPerRequest = maxEventsPerRequest,
        logger = logger,
        now = { FIXED_NOW },
    )

    private fun record(client: TelemetryClient) {
        client.record(TelemetryEvents.SDK_INITIALIZED, mapOf(TelemetryProperty.STATE.key to "ready"))
    }

    private companion object {
        val TEST_TIMEOUT = 10.seconds
        val FLUSH_INTERVAL = 30.seconds
        const val DEVICE = "9f2c4b7e1a05d38c6e4b90f7c2a1d5e3"
        const val FIXED_NOW = 1_755_000_000_000
    }
}
