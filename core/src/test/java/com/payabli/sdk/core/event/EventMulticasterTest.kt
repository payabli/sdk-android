package com.payabli.sdk.core.event

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds
private val DELIVERY_TIMEOUT = 2.seconds

/** Emitted after a stall is released, so a drained subscriber has one unambiguous last event to wait for. */
private const val SENTINEL = "sentinel"

/**
 * The delivery bound, and its limit.
 *
 * Every subscriber that keeps up sees every event; one further behind than the buffer loses the oldest, and
 * one not attached at all sees nothing. That last property is why this carries lifecycle events for watchers
 * rather than records that have to arrive.
 */
class EventMulticasterTest {
    private val subject = EventMulticaster<String>()

    /**
     * Subscribes and does not return until the subscription is registered.
     *
     * `onSubscription` rather than a `yield()`: the emitter drops rather than suspends, so an event emitted
     * into a not-yet-registered subscriber is lost silently, and a scheduling-dependent test would report
     * that as either outcome.
     */
    private suspend fun collectInto(
        target: MutableList<String>,
        scope: CoroutineScope,
        onEach: suspend () -> Unit = {},
    ): Job {
        val subscribed = CompletableDeferred<Unit>()
        val job =
            scope.launch {
                subject.events
                    .onSubscription { subscribed.complete(Unit) }
                    .collect {
                        target += it
                        onEach()
                    }
            }
        awaitOrFail("the subscription to register") { subscribed.await() }
        return job
    }

    /** Bounds a wait so a wedge names itself instead of running the suite out to its own timeout. */
    private suspend fun awaitOrFail(
        what: String,
        block: suspend () -> Unit,
    ) {
        val completed = withTimeoutOrNull(DELIVERY_TIMEOUT) { block() }
        assertTrue("timed out waiting for $what", completed != null)
    }

    /** Completes once [target] has reached [count] entries, for use as an [collectInto] `onEach`. */
    private fun countedTo(
        count: Int,
        target: List<String>,
        gate: CompletableDeferred<Unit>,
    ): suspend () -> Unit = { if (target.size >= count) gate.complete(Unit) }

    @Test
    fun `every active subscriber receives every event`() =
        runTest(timeout = TEST_TIMEOUT) {
            val first = mutableListOf<String>()
            val second = mutableListOf<String>()
            val third = mutableListOf<String>()
            val firstDone = CompletableDeferred<Unit>()
            val secondDone = CompletableDeferred<Unit>()
            val thirdDone = CompletableDeferred<Unit>()

            val jobs =
                listOf(
                    collectInto(first, this, countedTo(3, first, firstDone)),
                    collectInto(second, this, countedTo(3, second, secondDone)),
                    collectInto(third, this, countedTo(3, third, thirdDone)),
                )

            subject.emit("one")
            subject.emit("two")
            subject.emit("three")

            awaitOrFail("the first subscriber to see every event") { firstDone.await() }
            awaitOrFail("the second subscriber to see every event") { secondDone.await() }
            awaitOrFail("the third subscriber to see every event") { thirdDone.await() }

            val expected = listOf("one", "two", "three")
            assertEquals(expected, first)
            assertEquals(expected, second)
            assertEquals(expected, third)
            jobs.forEach { it.cancel() }
        }

    @Test
    fun `a subscriber sees nothing emitted before it subscribed`() =
        runTest(timeout = TEST_TIMEOUT) {
            subject.emit("before")

            val seen = mutableListOf<String>()
            val delivered = CompletableDeferred<Unit>()
            val job = collectInto(seen, this, countedTo(1, seen, delivered))

            subject.emit("after")
            awaitOrFail("the late subscriber to see the event that followed it") { delivered.await() }

            // The whole list, not merely "does not start with before": replay would have delivered it first,
            // so asserting the contents is what separates no-replay from a replay this happened to tolerate.
            assertEquals(listOf("after"), seen)
            job.cancel()
        }

    @Test
    fun `a stalled subscriber does not stall the emitter or its peers`() =
        runTest(timeout = TEST_TIMEOUT) {
            val stall = CompletableDeferred<Unit>()
            val stalled = mutableListOf<String>()
            val healthy = mutableListOf<String>()
            val healthyDone = CompletableDeferred<Unit>()

            val stalledJob = collectInto(stalled, this) { stall.await() }
            val healthyJob = collectInto(healthy, this, countedTo(2, healthy, healthyDone))

            subject.emit("one")
            subject.emit("two")

            awaitOrFail("the healthy subscriber to receive both events past a stalled peer") {
                healthyDone.await()
            }
            assertEquals(listOf("one", "two"), healthy)
            assertTrue("a stalled subscriber should not have advanced past its first event", stalled.size <= 1)

            stall.complete(Unit)
            stalledJob.cancel()
            healthyJob.cancel()
        }

    @Test
    fun `a subscriber further behind than the buffer keeps the newest events and loses the oldest`() =
        runTest(timeout = TEST_TIMEOUT) {
            val stall = CompletableDeferred<Unit>()
            val stalled = mutableListOf<String>()
            val sawSentinel = CompletableDeferred<Unit>()
            val stalledJob =
                collectInto(stalled, this) {
                    if (stalled.last() == SENTINEL) sawSentinel.complete(Unit) else stall.await()
                }

            // Two past what the buffer holds, so the loss is the bound being enforced rather than a
            // coincidence of scheduling: the collector is parked on event-0 while 1..65 contend for 64 slots.
            val overflow = BUFFER + 2
            repeat(overflow) { subject.emit("event-$it") }

            stall.complete(Unit)
            subject.emit(SENTINEL)
            awaitOrFail("the stalled subscriber to drain and reach the sentinel") { sawSentinel.await() }

            // The discriminating assertion, and the reason this test exists separately from the one above.
            // Not stalling the emitter is `tryEmit`'s doing and holds under any overflow policy, so it
            // cannot show DROP_OLDEST is in force. What only DROP_OLDEST gives is that the events surviving
            // an overflow are the most recent ones: under SUSPEND, `tryEmit` refuses once the buffer is full
            // and it is the newest that never arrives, which is the wrong half to lose for a transition.
            assertTrue(
                "the newest event should have survived the overflow, saw $stalled",
                stalled.contains("event-${overflow - 1}"),
            )
            assertTrue(
                "an event older than the buffer should have been dropped, saw ${stalled.size} of $overflow",
                !stalled.contains("event-1"),
            )
            stalledJob.cancel()
        }
}
