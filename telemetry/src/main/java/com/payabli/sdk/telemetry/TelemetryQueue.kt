package com.payabli.sdk.telemetry

import com.payabli.sdk.core.telemetry.TelemetrySessionContext

/**
 * One scrubbed event, waiting for a flush.
 *
 * The timestamp is taken when the event happened rather than when it ships, so a batch that waited for a
 * network still reports when each thing occurred.
 */
internal class QueuedTelemetryEvent(
    val name: String,
    val properties: Map<String, String>,
    val occurredAtMillis: Long,
    val session: TelemetrySessionContext,
)

/**
 * The bounded buffer between emitting and sending.
 *
 * **Bounded and drop-oldest.** An app with no network for an hour must not grow a queue until it is killed,
 * and when something has to go, the oldest event is the one whose absence costs least: the newest events are
 * the ones describing whatever is going wrong now.
 *
 * Locked rather than lock-free because emitting is not `suspend` and cannot wait on a mutex, and the critical
 * section is an append and a size check. [drain] takes the whole batch under the same lock, so a flush and an
 * emit racing cannot produce a batch missing an event that was queued before it.
 */
internal class TelemetryQueue(
    private val capacity: Int,
) {
    private val lock = Any()
    private val events = ArrayDeque<QueuedTelemetryEvent>()
    private var droppedSinceLastDrain = 0

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /** Adds [event], evicting the oldest if the queue is full. Returns the size afterwards. */
    fun offer(event: QueuedTelemetryEvent): Int =
        synchronized(lock) {
            if (events.size >= capacity) {
                events.removeFirst()
                droppedSinceLastDrain++
            }
            events.addLast(event)
            events.size
        }

    /** Removes and returns at most [max] events, oldest first. */
    fun drain(max: Int): List<QueuedTelemetryEvent> =
        synchronized(lock) {
            if (events.isEmpty()) {
                emptyList()
            } else {
                // Coerced, because `ArrayList(-1)` throws and a caller asking for nothing means nothing.
                val taken = ArrayList<QueuedTelemetryEvent>(minOf(max, events.size).coerceAtLeast(0))
                while (taken.size < max && events.isNotEmpty()) {
                    taken.add(events.removeFirst())
                }
                taken
            }
        }

    /** How many events are waiting. */
    fun size(): Int = synchronized(lock) { events.size }

    /**
     * How many events have been evicted since this was last asked, and resets the count.
     *
     * A queue that discards silently is how a gap in a chart gets mistaken for a quiet period, so the count
     * rides the next batch that is accepted. [restoreDropCount] puts it back when that batch is not.
     */
    fun takeDropCount(): Int =
        synchronized(lock) {
            val dropped = droppedSinceLastDrain
            droppedSinceLastDrain = 0
            dropped
        }

    /**
     * Returns an unreported count to the queue, to travel with the next batch that is accepted.
     *
     * Added rather than assigned, under the same lock: evictions carry on during a send, and overwriting
     * would discard whatever was counted while the request was in flight.
     */
    fun restoreDropCount(dropped: Int) {
        if (dropped <= 0) return
        synchronized(lock) { droppedSinceLastDrain += dropped }
    }
}
