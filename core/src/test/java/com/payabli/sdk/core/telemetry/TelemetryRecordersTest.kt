package com.payabli.sdk.core.telemetry

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryRecordersTest {
    @After
    fun clear() {
        TelemetryRecorders.clear()
    }

    @Test
    fun recordingWithNothingInstalledDoesNothing() {
        TelemetryRecorders.record(TelemetryEvents.SDK_INITIALIZED) { mapOf("state" to "ready") }
    }

    @Test
    fun anInstalledRecorderReceivesWhatWasRecorded() {
        val seen = mutableListOf<Pair<String, Map<String, String>>>()
        TelemetryRecorders.install { event, properties -> seen += event to properties }

        TelemetryRecorders.record(TelemetryEvents.SDK_INITIALIZED) { mapOf("state" to "ready") }

        assertEquals(listOf(TelemetryEvents.SDK_INITIALIZED to mapOf("state" to "ready")), seen)
    }

    @Test
    fun clearingStopsDelivery() {
        val seen = mutableListOf<String>()
        TelemetryRecorders.install { event, _ -> seen += event }
        TelemetryRecorders.clear()

        TelemetryRecorders.record(TelemetryEvents.SDK_INITIALIZED)

        assertTrue(seen.isEmpty())
    }

    /**
     * The guarantee that lets a payment path call this on its critical line. Without it, a defect anywhere in
     * a reporting channel becomes a failed charge.
     *
     * **Several unrelated types, because one type proves nothing here.** The catch is written against
     * `RuntimeException`, and a single `IllegalStateException` left that untested: narrowing the catch to
     * exactly that type kept this green. These four share no supertype below `RuntimeException`, so any
     * narrowing turns it red. They are also what the real path can raise — a full queue, a value the wire
     * models refuse, a state the client is not in, and a dead executor.
     */
    @Test
    fun aRecorderThatThrowsDoesNotReachTheCaller() {
        val failures =
            listOf(
                IllegalStateException("the client is stopped"),
                IllegalArgumentException("the event is not serializable"),
                ConcurrentModificationException("the queue changed under the flush"),
                java.util.NoSuchElementException("the batch was drained"),
            )

        failures.forEach { failure ->
            TelemetryRecorders.install { _, _ -> throw failure }

            TelemetryRecorders.record(TelemetryEvents.SDK_INITIALIZED) { mapOf("state" to "ready") }
        }
    }

    /**
     * The other side of that line, and the reason the catch names `RuntimeException` rather than `Throwable`.
     *
     * An exhausted heap or a missing class is not a reporting failure to absorb: swallowing it would leave
     * the process running in a state nothing else can reason about, and hide it behind a warning about
     * telemetry.
     */
    @Test(expected = OutOfMemoryError::class)
    fun anErrorIsNotAbsorbed() {
        TelemetryRecorders.install { _, _ -> throw OutOfMemoryError("the heap is gone") }

        TelemetryRecorders.record(TelemetryEvents.SDK_INITIALIZED)
    }

    /**
     * The assertion that keeps the laziness from being simplified away.
     *
     * An eager parameter would build the map on every instrumented operation in every app that never linked
     * the module, and nothing about that shows up as a failure: the events are identical either way.
     */
    @Test
    fun withNothingInstalledThePropertiesAreNeverBuilt() {
        var built = 0

        TelemetryRecorders.record(TelemetryEvents.SDK_INITIALIZED) {
            built++
            mapOf("state" to "ready")
        }

        assertEquals(0, built)
    }

    @Test
    fun theyAreBuiltOnceWhenSomethingIsListening() {
        var built = 0
        TelemetryRecorders.install { _, _ -> }

        TelemetryRecorders.record(TelemetryEvents.SDK_INITIALIZED) {
            built++
            mapOf("state" to "ready")
        }

        assertEquals(1, built)
    }

    @Test
    fun installingAgainReplacesTheOneBefore() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        TelemetryRecorders.install { event, _ -> first += event }
        TelemetryRecorders.install { event, _ -> second += event }

        TelemetryRecorders.record(TelemetryEvents.SDK_INITIALIZED)

        assertTrue(first.isEmpty())
        assertEquals(listOf(TelemetryEvents.SDK_INITIALIZED), second)
    }
}
