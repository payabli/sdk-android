package com.payabli.sdk.core.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * Documents the cancellation semantics behind PayabliAuth's NonCancellable cleanup.
 *
 * The important distinction is not "withLock always observes cancellation". It does not: an uncontended
 * mutex can be acquired on the fast path without suspending. The dangerous case is a cancelled coroutine
 * trying to acquire a contended mutex, because that acquisition must suspend and Mutex.lock is cancellable.
 */
class MutexCancellationSemanticsTest {
    @Test
    fun `uncontended withLock in an already cancelled coroutine may acquire on the fast path`() =
        runTest(timeout = TEST_TIMEOUT) {
            val mutex = Mutex()
            val ready = CompletableDeferred<Unit>()

            var acquired = false
            var thrown: Throwable? = null

            val job =
                launch {
                    try {
                        ready.complete(Unit)
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        thrown =
                            runCatching {
                                mutex.withLock {
                                    acquired = true
                                }
                            }.exceptionOrNull()
                    }
                }

            ready.await()
            job.cancelAndJoin()

            Assert.assertTrue(
                "uncontended withLock acquired before observing cancellation",
                acquired,
            )
            Assert.assertNull("fast-path acquisition should not throw", thrown)
        }

    @Test
    fun `contended withLock in an already cancelled coroutine observes cancellation`() =
        runTest(timeout = TEST_TIMEOUT) {
            val mutex = Mutex(locked = true)
            val ready = CompletableDeferred<Unit>()

            var acquired = false
            var thrown: Throwable? = null

            val job =
                launch {
                    try {
                        ready.complete(Unit)
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        thrown =
                            runCatching {
                                mutex.withLock {
                                    acquired = true
                                }
                            }.exceptionOrNull()
                    }
                }

            ready.await()
            job.cancelAndJoin()

            Assert.assertTrue(
                "lock acquisition must throw CancellationException",
                thrown is CancellationException,
            )
            Assert.assertEquals(
                "cancelled coroutine must not enter the critical section",
                false,
                acquired,
            )
        }

    @Test
    fun `NonCancellable contended withLock waits instead of throwing cancellation`() =
        runTest(timeout = TEST_TIMEOUT) {
            val mutex = Mutex(locked = true)
            val ready = CompletableDeferred<Unit>()
            val waitingForLock = CompletableDeferred<Unit>()

            var result: String? = null
            var thrown: Throwable? = null

            val job =
                launch {
                    try {
                        ready.complete(Unit)
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        thrown =
                            runCatching {
                                result =
                                    withContext(NonCancellable) {
                                        waitingForLock.complete(Unit)
                                        mutex.withLock {
                                            "acquired"
                                        }
                                    }
                            }.exceptionOrNull()
                    }
                }

            ready.await()
            job.cancel()
            waitingForLock.await()

            val stillWaiting =
                withTimeoutOrNull(100) {
                    job.join()
                    "completed"
                }

            Assert.assertNull(
                "NonCancellable should wait for the mutex, not throw immediately",
                stillWaiting,
            )
            Assert.assertNull(
                "no cancellation should be thrown while waiting under NonCancellable",
                thrown,
            )

            mutex.unlock()
            job.join()

            Assert.assertEquals("acquired", result)
            Assert.assertNull(thrown)
        }
}
