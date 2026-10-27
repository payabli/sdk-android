package com.payabli.sdk.telemetry

import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.telemetry.SessionScopedRecorder
import com.payabli.sdk.core.telemetry.TelemetryCatalog
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperty
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
    init {
        // The overflow event takes one of a request's places, so a request needs one left for anything else.
        require(maxEventsPerRequest >= 2) { "maxEventsPerRequest must be at least 2" }
    }

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

        // A reporting thread can read this client from the registry and be descheduled before it gets here,
        // and a replacement in that window stops this one first. The queue refuses under the same lock it
        // appends with, so the answer cannot be overtaken by the drain that follows.
        val offered = queue.offer(QueuedTelemetryEvent(event, scrubbed, now(), session))
        if (offered == null) {
            logger.debug(
                LogField.safe("event", "telemetry_event_after_stop"),
                LogField.safe("name", event),
            ) { "this channel was replaced while the event was being recorded; dropped" }
            return
        }

        // Counts what was offered since the last send attempt, so a refused batch back on the queue does not
        // make every later event a full batch.
        if (offered >= batchSize || TelemetryCatalog.forcesSend(event, scrubbed)) flushAsync()
    }

    /** Starts the periodic flush. Idempotent: a second call leaves the running timer alone. */
    @Synchronized
    fun start() {
        if (timer != null) return
        // Signals rather than flushes. Flushing here put the upload on the timer's own coroutine, so a
        // `stop` that cancelled the timer mid-send cancelled the send: the batch was already drained and its
        // drop count already taken, and the shutdown drain could not see either. Cancelling this now ends a
        // delay loop, and the worker either finishes or serialises with that drain on the same lock.
        timer =
            scope.launch {
                while (isActive) {
                    delay(flushInterval)
                    flushAsync()
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
        queue.close()
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
        // One place more than is queued, for an overflow event. A flush left with nothing to send makes no
        // request and ends the loop, so the place costs nothing when no count is waiting.
        val waiting = queue.size() + 1
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
     * Sends one request's worth, at most, and answers whether the drain it feeds may continue.
     *
     * Not a loop until empty: a burst refills as it drains. A batch that went back to the queue's
     * front is resent at once by a continued drain, so its answer is false; a discarded or dropped
     * one has nothing left to resend, and the drain carries on.
     */
    suspend fun flush(): Boolean =
        sending.withLock {
            val dropped = queue.takeDropCount()
            // The overflow event takes one of the request's places, so a request never carries more than
            // `maxEventsPerRequest` events.
            val drained = queue.drain(if (dropped > 0) maxEventsPerRequest - 1 else maxEventsPerRequest)

            // `takeDropCount` resets as it reads, so a count taken with nothing to send goes back for the
            // batch that has something.
            if (drained.isEmpty()) {
                queue.restoreDropCount(dropped)
                return@withLock false
            }

            if (dropped > 0) {
                logger.warn(
                    LogField.safe("event", "telemetry_queue_overflow"),
                    LogField.safe("dropped", dropped),
                ) { "queue was full; oldest events evicted" }
            }
            val batch = if (dropped > 0) drained + overflowEvent(dropped) else drained

            when (uploader.send(batch)) {
                SendOutcome.SENT -> true
                SendOutcome.KEEP -> {
                    // The count goes back beside the events rather than as an event among them, so the
                    // events offered during the send can evict the batch but never the count. A channel
                    // stopped mid-send refuses the batch, and its events are lost with it.
                    val requeued = queue.pushFront(drained)
                    queue.restoreDropCount(dropped)
                    if (requeued) {
                        false
                    } else {
                        logger.debug(
                            LogField.safe("event", "telemetry_batch_after_stop"),
                            LogField.safe("count", drained.size),
                        ) { "the channel stopped while the batch was being sent; dropped" }
                        true
                    }
                }
                SendOutcome.DISCARD -> {
                    queue.restoreDropCount(dropped)
                    true
                }
            }
        }

    /**
     * The event that reports [dropped] evictions, last in its batch and timed when the flush noticed them.
     *
     * Its properties pass through the catalog's scrub, so it carries only what the catalog declares.
     *
     * @param dropped how many events the queue evicted, more than zero.
     * @return the event to append to the batch.
     */
    private fun overflowEvent(dropped: Int): QueuedTelemetryEvent {
        val properties = mapOf(TelemetryProperty.DROPPED.key to dropped.toString())
        return QueuedTelemetryEvent(
            name = TelemetryEvents.TELEMETRY_OVERFLOW,
            properties = TelemetryCatalog.scrub(TelemetryEvents.TELEMETRY_OVERFLOW, properties).orEmpty(),
            occurredAtMillis = now(),
            session = context,
        )
    }
}

/** Enough requests to empty a full queue at this module's sizes, with room to spare. */
private const val DEFAULT_MAX_DRAIN_REQUESTS = 16
