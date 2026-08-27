package com.payabli.sdk.telemetry

import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.telemetry.SessionScopedRecorder
import com.payabli.sdk.core.telemetry.TelemetryCatalog
import com.payabli.sdk.core.telemetry.TelemetryRecorder
import com.payabli.sdk.core.telemetry.TelemetrySessionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
 * Four things cause a flush, and they answer different failures: a full batch, so a busy app ships
 * continuously; a timer, so a quiet app ships at all; the app going to the background, which is the last
 * moment before a process that may never run again; and an event the catalog says cannot wait, which is a
 * name that already means failure or an outcome that is not a success.
 */
internal class TelemetryClient(
    private val context: TelemetrySessionContext,
    private val queue: TelemetryQueue,
    private val uploader: TelemetryUploader,
    private val scope: CoroutineScope,
    private val flushInterval: Duration,
    private val batchSize: Int,
    private val maxEventsPerRequest: Int,
    private val logger: SdkLogger,
    private val now: () -> Long,
    /** Most requests a drain sends: enough to empty a full queue, capped so one refilling cannot hold it open. */
    private val maxDrainRequests: Int = DEFAULT_MAX_DRAIN_REQUESTS,
) : TelemetryRecorder,
    SessionScopedRecorder {
    private val sending = Mutex()

    /** Requests to flush. Conflated, so one made while a flush runs is kept rather than dropped. */
    private val flushRequests = Channel<Unit>(Channel.CONFLATED)

    /** In `init` rather than [start]: [record] can flush before the timer starts, and no consumer loses it. */
    private val flushes: Job =
        scope.launch {
            for (request in flushRequests) {
                drainWhatWasWaiting()
            }
        }

    @Volatile
    private var timer: Job? = null

    override fun record(
        event: String,
        properties: Map<String, String>,
        session: TelemetrySessionContext,
    ) {
        // The batch's entry is what the request is authorized against, and the service drops an event whose
        // own entry differs from it, so a record from another entry point cannot be delivered on this channel.
        if (session.entryPoint != context.entryPoint || session.environment != context.environment) {
            logger.debug(
                LogField.safe("event", "telemetry_event_foreign_session"),
                LogField.safe("name", event),
            ) { "the session this event belongs to is no longer the one reporting; dropped" }
            return
        }
        enqueue(event, properties, session)
    }

    override fun record(
        event: String,
        properties: Map<String, String>,
    ) {
        enqueue(event, properties, context)
    }

    private fun enqueue(
        event: String,
        properties: Map<String, String>,
        session: TelemetrySessionContext,
    ) {
        val scrubbed = TelemetryCatalog.scrub(event, properties)
        if (scrubbed == null) {
            logger.warn(LogField.safe("event", "telemetry_event_unknown")) { "event not in the catalog; dropped" }
            return
        }

        val queued = queue.offer(QueuedTelemetryEvent(event, scrubbed, now(), session))

        if (queued >= batchSize || TelemetryCatalog.forcesSend(event, scrubbed)) flushAsync()
    }

    /** Starts the periodic flush. Idempotent: a second call leaves the running timer alone. */
    @Synchronized
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
     * Drains rather than flushing once: [flush] sends one request's worth and the queue holds more. The loop
     * terminates because the recorder is detached before this runs, and the bound is there so a caller still
     * writing cannot hold shutdown open.
     */
    @Synchronized
    fun stop() {
        timer?.cancel()
        timer = null
        flushRequests.close()
        scope
            .launch {
                var requests = 0
                while (requests < maxDrainRequests && flush()) {
                    requests++
                }
            }.invokeOnCompletion { scope.cancel() }
    }

    /**
     * Sends what was queued when the signal was taken, in as many requests as that needs.
     *
     * The channel conflates, so one signal can stand for a burst. Bounded by the count read here rather than
     * by the queue emptying: what arrives during these requests carries its own signal.
     */
    private suspend fun drainWhatWasWaiting() {
        val waiting = queue.size()
        var requests = ((waiting + maxEventsPerRequest - 1) / maxEventsPerRequest).coerceIn(1, maxDrainRequests)
        while (requests > 0 && flush()) {
            requests--
        }
    }

    /** Asks for a flush. The `Unit` is a signal: one of these drains what is waiting. */
    fun flushAsync() {
        flushRequests.trySend(Unit)
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

            // Taken only when something is going out: `takeDropCount` resets as it reads, so a count taken
            // with nothing to send is a count nobody sees.
            if (batch.isEmpty()) return@withLock false

            val dropped = queue.takeDropCount()
            if (dropped > 0) {
                logger.warn(
                    LogField.safe("event", "telemetry_queue_overflow"),
                    LogField.safe("dropped", dropped),
                ) { "queue was full; oldest events evicted" }
            }

            // The response says nothing about a refused batch's content, so its events are not retried.
            // The count describes the gap rather than filling it, and goes back to the queue.
            if (!uploader.send(batch, dropped)) queue.restoreDropCount(dropped)
            true
        }
}

/** Enough requests to empty a full queue at this module's sizes, with room to spare. */
private const val DEFAULT_MAX_DRAIN_REQUESTS = 16
