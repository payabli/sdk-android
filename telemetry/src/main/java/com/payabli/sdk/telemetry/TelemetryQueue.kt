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
    private var offeredSinceLastDrain = 0
    private var closed = false

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /**
     * Adds [event] and answers how many events have been offered since the last [drain], or null once [close]
     * has been called.
     *
     * The count is of offers, not of what is waiting: a batch returned by [pushFront] is not counted.
     *
     * The refusal is inside the lock with the append, because the two cannot be separated: a caller that
     * tested a flag outside it could be overtaken by the shutdown drain and land afterwards, leaving the
     * event in a queue nothing will read again. This lock is the one every enqueue already takes.
     *
     * @param event the scrubbed event to queue.
     * @return the events offered since the last drain, this one included, or null once closed.
     */
    fun offer(event: QueuedTelemetryEvent): Int? =
        synchronized(lock) {
            if (closed) return@synchronized null
            if (events.size >= capacity) {
                events.removeFirst()
                droppedSinceLastDrain++
            }
            events.addLast(event)
            ++offeredSinceLastDrain
        }

    /**
     * Removes and returns at most [max] events, oldest first, and restarts the count [offer] answers.
     *
     * Only the client's send drains, so a drain that took anything is a send attempt.
     */
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
                if (taken.isNotEmpty()) offeredSinceLastDrain = 0
                taken
            }
        }

    /**
     * Returns a batch that was not delivered to the front of the queue, in its order.
     *
     * Overflow evicts from the front as [offer] does, so the oldest go: a batch that keeps failing is
     * evicted by the newer events filling the queue behind it.
     *
     * @param batch the batch's events, oldest first.
     * @return true when the events were queued again, and false once [close] has been called.
     */
    fun pushFront(batch: List<QueuedTelemetryEvent>): Boolean =
        synchronized(lock) {
            if (closed) return@synchronized false
            for (index in batch.indices.reversed()) events.addFirst(batch[index])
            while (events.size > capacity) {
                events.removeFirst()
                droppedSinceLastDrain++
            }
            true
        }

    /**
     * Refuses everything offered from here on, so a shutdown drain is the last read of this queue.
     *
     * [drain] still works, because the drain runs after this.
     */
    fun close() = synchronized(lock) { closed = true }

    /** How many events are waiting. */
    fun size(): Int = synchronized(lock) { events.size }

    /**
     * How many events have been evicted since this was last asked, and resets the count.
     *
     * A queue that discards silently is how a gap in a chart gets mistaken for a quiet period, so the count
     * becomes an event in the next batch. [restoreDropCount] puts it back when no accepted batch carried it.
     */
    fun takeDropCount(): Int =
        synchronized(lock) {
            val dropped = droppedSinceLastDrain
            droppedSinceLastDrain = 0
            dropped
        }

    /**
     * Returns an unreported count to the queue, for the next batch to carry.
     *
     * Added rather than assigned, under the same lock: evictions carry on during a send, and overwriting
     * would discard whatever was counted while the request was in flight.
     */
    fun restoreDropCount(dropped: Int) {
        if (dropped <= 0) return
        synchronized(lock) { droppedSinceLastDrain += dropped }
    }
}
