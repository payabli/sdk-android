package com.payabli.sdk.telemetry

import org.junit.Assert.assertEquals
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
    fun afullQueueEvictsTheOldest() {
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

    private fun event(index: Int) =
        QueuedTelemetryEvent(
            name = "e$index",
            properties = emptyMap(),
            occurredAtMillis = index.toLong(),
        )
}
