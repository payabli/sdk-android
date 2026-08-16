package com.payabli.sdk.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The sink is written by every request a transport has in flight, so it has to survive concurrent writers.
 *
 * Backed by an `ArrayList` it does not: the count comes back short, and the same race throws out of
 * `ArrayList.add` often enough to redden a run with nothing wrong in it. Both failures land on whichever test
 * was running rather than here, which is what makes them expensive to place.
 */
class RecordingLogSinkConcurrencyTest {
    @Test
    fun everyWriteFromEveryThreadIsRecorded() {
        val sink = RecordingLogSink()
        val pool = Executors.newFixedThreadPool(WRITERS)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(WRITERS)

        try {
            repeat(WRITERS) {
                pool.submit {
                    start.await()
                    repeat(WRITES_EACH) { sink.write(LogLevel.DEBUG, "tag", "message") }
                    finished.countDown()
                }
            }
            start.countDown()

            assertTrue("a writer never finished", finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(WRITERS * WRITES_EACH, sink.records.size)
        } finally {
            // In a finally, so a failed assertion leaves no writers behind for whatever test runs next, and
            // awaited, because shutdownNow interrupts without waiting for anything to notice.
            pool.shutdownNow()
            assertTrue("the pool outlived the test", pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    private companion object {
        const val WRITERS = 8
        const val WRITES_EACH = 500
        const val TIMEOUT_SECONDS = 30L
    }
}
