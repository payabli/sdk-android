package com.payabli.sdk.telemetry

import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.telemetry.TelemetryCatalog
import com.payabli.sdk.core.telemetry.TelemetryRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * Scrub, queue, and ship: the recorder every emitting site reaches through `TelemetryRecorders`.
 *
 * **Scrubbing happens on the emitting thread, before the event is queued.** That is the whole reason
 * [record] does work at all rather than handing the raw map to a coroutine: a value that should not be
 * retained must not be retained even briefly, and a queue is retention.
 *
 * Everything after that is asynchronous and best-effort. Nothing here blocks the caller and nothing here
 * reaches the caller, whatever happens to the batch.
 *
 * Three things cause a flush, and they answer different failures: a full batch, so a busy app ships
 * continuously; a timer, so a quiet app ships at all; and the app going to the background, which is the last
 * moment before a process that may never run again.
 */
internal class TelemetryClient(
    private val queue: TelemetryQueue,
    private val uploader: TelemetryUploader,
    private val scope: CoroutineScope,
    private val flushInterval: Duration,
    private val batchSize: Int,
    private val maxEventsPerRequest: Int,
    private val logger: SdkLogger,
    private val now: () -> Long,
) : TelemetryRecorder {
    private val sending = Mutex()

    @Volatile
    private var timer: Job? = null

    override fun record(
        event: String,
        properties: Map<String, String>,
    ) {
        val scrubbed = TelemetryCatalog.scrub(event, properties)
        if (scrubbed == null) {
            logger.warn(LogField.safe("event", "telemetry_event_unknown")) { "event not in the catalog; dropped" }
            return
        }

        val queued = queue.offer(QueuedTelemetryEvent(event, scrubbed, now()))
        if (queued >= batchSize) flushAsync()
    }

    /** Starts the periodic flush. Idempotent: a second call leaves the running timer alone. */
    fun start() {
        if (timer != null) return
        timer =
            scope.launch {
                while (isActive) {
                    delay(flushInterval)
                    flush()
                }
            }
    }

    /**
     * Sends the last batch and stops.
     *
     * The final flush runs before the scope is cancelled, so events already queued still leave rather than
     * dying with the session that produced them.
     */
    fun stop() {
        timer?.cancel()
        timer = null
        scope.launch { flush() }.invokeOnCompletion { scope.cancel() }
    }

    /** Flushes without waiting, for the callers that are not in a coroutine. */
    fun flushAsync() {
        scope.launch { flush() }
    }

    /**
     * Sends one request's worth, at most.
     *
     * Not a loop until empty: a burst refills as fast as it drains, and a flush that chased it would hold
     * this coroutine and the queue's lock against every emitting thread. Whatever is left is picked up by the
     * next full batch or the next tick.
     */
    suspend fun flush() {
        sending.withLock {
            val batch = queue.drain(maxEventsPerRequest)
            val dropped = queue.takeDropCount()
            if (dropped > 0) {
                logger.debug(
                    LogField.safe("event", "telemetry_queue_overflow"),
                    LogField.safe("dropped", dropped),
                ) { "queue was full; oldest events evicted" }
            }
            if (batch.isEmpty()) return@withLock
            uploader.send(batch)
        }
    }
}
