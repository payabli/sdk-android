package com.payabli.sdk.telemetry

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
                val taken = ArrayList<QueuedTelemetryEvent>(minOf(max, events.size))
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
     * Read once per flush and reported in a log record. Nothing else learns that events were lost, and a
     * queue silently discarding is how a gap in a chart gets mistaken for a quiet period.
     */
    fun takeDropCount(): Int =
        synchronized(lock) {
            val dropped = droppedSinceLastDrain
            droppedSinceLastDrain = 0
            dropped
        }
}
