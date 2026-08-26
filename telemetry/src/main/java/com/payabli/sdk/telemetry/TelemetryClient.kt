package com.payabli.sdk.telemetry

import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.SdkLogger
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
import java.util.concurrent.atomic.AtomicBoolean
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
    /**
     * Most requests a shutdown drain will send.
     *
     * Sized from the queue rather than guessed: enough to empty a full one, and a ceiling so a client still
     * being written to cannot hold shutdown open.
     */
    private val maxDrainRequests: Int = DEFAULT_MAX_DRAIN_REQUESTS,
) : TelemetryRecorder {
    private val sending = Mutex()

    /**
     * Whether a flush is already on its way, so a full queue schedules one rather than one per event.
     *
     * Without it every event past [batchSize] launched its own coroutine. The queue is bounded and those
     * jobs were not: against a slow or offline upload they all park on [sending], and a busy app grows the
     * process by event volume while the queue it is bounded by never moves. Coalescing is what makes the
     * bound on the queue a bound on the client.
     */
    private val flushScheduled = AtomicBoolean(false)

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
     * Sends everything queued and stops.
     *
     * **Drains rather than flushing once.** [flush] sends one request's worth, and the queue holds far more
     * than one request's worth, so a single final flush left the rest to die with the scope it was about to
     * cancel: a burst before shutdown lost everything past the first batch, silently, against a contract
     * that says queued events leave.
     *
     * The loop terminates because the recorder is detached before this runs, so nothing is producing. The
     * bound is there anyway: a caller that still holds the client could otherwise refill it forever, and a
     * shutdown that does not finish is worse than one that drops the tail it names.
     */
    fun stop() {
        timer?.cancel()
        timer = null
        scope
            .launch {
                var requests = 0
                while (requests < maxDrainRequests && flush()) {
                    requests++
                }
            }.invokeOnCompletion { scope.cancel() }
    }

    /**
     * Flushes without waiting, for the callers that are not in a coroutine.
     *
     * At most one is in flight. Another arriving while one is scheduled is dropped rather than queued: the
     * queue it would drain is the same queue, and whatever it would have carried the scheduled one carries.
     */
    fun flushAsync() {
        if (!flushScheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                flush()
            } finally {
                flushScheduled.set(false)
            }
        }
    }

    /**
     * Sends one request's worth, at most, and answers whether it sent anything.
     *
     * Not a loop until empty: a burst refills as fast as it drains, and a flush that chased it would hold
     * this coroutine and the queue's lock against every emitting thread. Whatever is left is picked up by the
     * next full batch or the next tick. Shutdown is the exception and loops in [stop], because there is no
     * next tick.
     *
     * The return value is for that loop alone. Nothing else asks: a flush that sent nothing and a flush that
     * failed are the same answer to every other caller, which is that reporting is best effort.
     */
    suspend fun flush(): Boolean =
        sending.withLock {
            val batch = queue.drain(maxEventsPerRequest)
            val dropped = queue.takeDropCount()
            if (dropped > 0) {
                logger.warn(
                    LogField.safe("event", "telemetry_queue_overflow"),
                    LogField.safe("dropped", dropped),
                ) { "queue was full; oldest events evicted" }
            }
            if (batch.isEmpty()) return@withLock false
            uploader.send(batch)
            true
        }
}

/** Enough requests to empty a full queue at this module's sizes, with room to spare. */
private const val DEFAULT_MAX_DRAIN_REQUESTS = 16
