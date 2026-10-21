package com.payabli.sdk.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryQueueTest {
    @Test
    fun eventsComeBackOldestFirst() {
        val queue = TelemetryQueue(capacity = 8)
        (1..3).forEach { queue.offer(event(it)) }

        assertEquals(listOf("e1", "e2", "e3"), queue.drain(8).map { it.name })
    }

    @Test
    fun drainingTakesAtMostWhatWasAskedFor() {
        val queue = TelemetryQueue(capacity = 8)
        (1..5).forEach { queue.offer(event(it)) }

        assertEquals(listOf("e1", "e2"), queue.drain(2).map { it.name })
        assertEquals(3, queue.size())
    }

    /** Full means the oldest goes, because the newest events are the ones describing what is going wrong now. */
    @Test
    fun aFullQueueEvictsTheOldest() {
        val queue = TelemetryQueue(capacity = 3)
        (1..5).forEach { queue.offer(event(it)) }

        assertEquals(listOf("e3", "e4", "e5"), queue.drain(8).map { it.name })
    }

    @Test
    fun evictionsAreCountedOnceAndThenReset() {
        val queue = TelemetryQueue(capacity = 2)
        (1..5).forEach { queue.offer(event(it)) }

        assertEquals(3, queue.takeDropCount())
        assertEquals(0, queue.takeDropCount())
    }

    @Test
    fun drainingAnEmptyQueueIsNotAFailure() {
        assertEquals(emptyList<QueuedTelemetryEvent>(), TelemetryQueue(capacity = 2).drain(4))
    }

    /**
     * A closed queue refuses, and refuses under the lock it appends with.
     *
     * The two cannot be separated. A caller that read a flag outside this lock could be overtaken by the
     * shutdown drain and land afterwards, leaving its event in a queue nothing reads again, which is a loss
     * with no drain left to catch it and no answer for the caller to report.
     */
    @Test
    fun aClosedQueueRefusesRatherThanAccepting() {
        val queue = TelemetryQueue(capacity = 8)
        assertEquals(1, queue.offer(event(1)))

        queue.close()

        assertNull(queue.offer(event(2)))
        assertEquals(listOf("e1"), queue.drain(8).map { it.name })
    }

    @Test
    fun aReturnedBatchGoesToFrontInItsOrder() {
        val queue = TelemetryQueue(capacity = 8)
        (1..4).forEach { queue.offer(event(it)) }
        val drained = queue.drain(2)
        (5..6).forEach { queue.offer(event(it)) }

        queue.pushFront(drained)

        assertEquals(listOf("e1", "e2", "e3", "e4", "e5", "e6"), queue.drain(8).map { it.name })
    }

    /**
     * A full queue evicts the oldest of a returned batch, and counts it.
     *
     * The returned events are the oldest once they are back, so a batch that keeps failing is evicted by
     * the newer events filling the queue behind it.
     */
    @Test
    fun aFullQueueEvictsTheOldestReturnedEvent() {
        val queue = TelemetryQueue(capacity = 4)
        (1..4).forEach { queue.offer(event(it)) }
        val drained = queue.drain(2)
        queue.offer(event(5))

        queue.pushFront(drained)

        assertEquals(listOf("e2", "e3", "e4", "e5"), queue.drain(8).map { it.name })
        assertEquals(1, queue.takeDropCount())
    }

    @Test
    fun aClosedQueueRefusesAReturnedBatch() {
        val queue = TelemetryQueue(capacity = 8)
        (1..2).forEach { queue.offer(event(it)) }
        val drained = queue.drain(2)

        queue.close()

        assertFalse(queue.pushFront(drained))
        assertEquals(emptyList<String>(), queue.drain(8).map { it.name })
    }

    private fun event(index: Int) =
        QueuedTelemetryEvent(
            name = "e$index",
            properties = emptyMap(),
            occurredAtMillis = index.toLong(),
            session = aSession(),
        )
}
