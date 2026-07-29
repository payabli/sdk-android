package com.payabli.sdk.core.auth

import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.config.PayabliTokenProvider
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultPayabliLogger
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds
private val COMPLETION_TIMEOUT = 2.seconds

/** The 2.2 auth holder: refresh de-duplication, the change flow, and how a provider failure surfaces. */
class PayabliAuthTest {
    private val sink = RecordingLogSink()

    private fun auth(tokenProvider: PayabliTokenProvider? = null) =
        PayabliAuth(
            PayabliConfig(
                accessToken = "initial-token",
                entryPoint = "entry",
                environment = PayabliEnvironment.SANDBOX,
                tokenProvider = tokenProvider,
            ),
            DefaultPayabliLogger(LogCategory.AUTH, sink),
        )

    /**
     * Bounded so a stranded claim fails with what went wrong, rather than hanging until the test
     * framework gives up and reports an uncompleted coroutine.
     */
    private suspend fun completing(
        what: String,
        block: suspend () -> String,
    ): String =
        withTimeoutOrNull(COMPLETION_TIMEOUT) { block() }
            ?: throw AssertionError("$what never completed: the refresh claim was stranded")

    private suspend fun failureFrom(block: suspend () -> Unit): PayabliException {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
        return thrown as PayabliException
    }

    @Test
    fun `the initial token comes from the config`() =
        runTest(timeout = TEST_TIMEOUT) {
            assertEquals("initial-token", auth().accessToken())
        }

    @Test
    fun `concurrent refreshes invoke the provider exactly once`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            val subject =
                auth {
                    calls.incrementAndGet()
                    gate.await()
                    "fresh-token"
                }

            // Five callers, all arriving while the first provider call is still parked on the gate.
            val waiters = List(5) { async { subject.invalidateAndRefresh("initial-token") } }
            while (calls.get() == 0) yield()
            gate.complete(Unit)

            assertEquals(List(5) { "fresh-token" }, waiters.map { it.await() })
            assertEquals("exactly one provider invocation", 1, calls.get())
            assertEquals("fresh-token", subject.accessToken())
        }

    @Test
    fun `a later refresh invokes the provider again`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val subject = auth { "token-${calls.incrementAndGet()}" }

            assertEquals("token-1", subject.invalidateAndRefresh("initial-token"))
            // Rejected on what is now current, so this is a genuine second rotation.
            assertEquals("token-2", subject.invalidateAndRefresh("token-1"))
            assertEquals(2, calls.get())
        }

    @Test
    fun `the change flow emits once per successful refresh`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val subject = auth { "token-${calls.incrementAndGet()}" }
            val seen = mutableListOf<String>()
            val collector = launch { subject.tokenChanges.collect { seen += it } }
            yield()

            subject.invalidateAndRefresh("initial-token")
            yield()
            subject.invalidateAndRefresh("token-1")
            yield()

            assertEquals(listOf("token-1", "token-2"), seen)
            collector.cancel()
        }

    @Test
    fun `de-duplicated waiters see one emission, not one each`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gate = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val subject =
                auth {
                    calls.incrementAndGet()
                    gate.await()
                    "fresh-token"
                }
            val seen = mutableListOf<String>()
            val collector = launch { subject.tokenChanges.collect { seen += it } }
            yield()

            val waiters = List(3) { async { subject.invalidateAndRefresh("initial-token") } }
            while (calls.get() == 0) yield()
            gate.complete(Unit)
            waiters.forEach { it.await() }
            yield()

            assertEquals(listOf("fresh-token"), seen)
            collector.cancel()
        }

    /**
     * The sink buffers one and drops the oldest so an emit never suspends. Both other flow tests yield
     * after each refresh, so the collector always drains the slot and the overflow branch is never taken.
     * A collector stalled across several rotations is what makes the buffering load-bearing: with a
     * suspending sink the refresh itself would block behind the host's collector.
     *
     * Asserts the two properties the strategy guarantees rather than an exact list, because how many
     * survive depends on when the collector happens to retrieve a value.
     */
    @Test
    fun `rotations while a collector is stalled complete, keeping the newest and dropping the rest`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val subject = auth { "token-${calls.incrementAndGet()}" }
            val release = CompletableDeferred<Unit>()
            val seen = mutableListOf<String>()

            val collector =
                launch {
                    subject.tokenChanges.collect {
                        release.await()
                        seen += it
                    }
                }
            yield()

            // Nobody is draining the sink for the second and third of these.
            completing("the first rotation") { subject.invalidateAndRefresh("initial-token") }
            completing("the second rotation") { subject.invalidateAndRefresh("token-1") }
            completing("the third rotation") { subject.invalidateAndRefresh("token-2") }

            release.complete(Unit)
            yield()

            assertEquals("the newest rotation must survive", "token-3", seen.lastOrNull())
            assertFalse("an intermediate rotation must be dropped, not queued", seen.contains("token-2"))
            collector.cancel()
        }

    @Test
    fun `no provider makes an expired token terminal`() =
        runTest(timeout = TEST_TIMEOUT) {
            val subject = auth(tokenProvider = null)

            val failure = failureFrom { subject.invalidateAndRefresh("initial-token") }

            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)
            assertEquals("the token is unchanged", "initial-token", subject.accessToken())
        }

    @Test
    fun `a provider failure surfaces as token expired without its own message`() =
        runTest(timeout = TEST_TIMEOUT) {
            val sentinel = "SENTINEL-BACKEND-BODY"
            val subject = auth { throw IOException("host backend said: $sentinel") }

            val failure = failureFrom { subject.invalidateAndRefresh("initial-token") }

            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)
            assertFalse(
                "the provider's message reached the caller",
                failure.stackTraceToString().contains(sentinel),
            )
        }

    @Test
    fun `waiters receive the same failure as the initiator, not the raw provider error`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gate = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val subject =
                auth {
                    calls.incrementAndGet()
                    gate.await()
                    throw IOException("provider down")
                }

            val waiters = List(3) { async { runCatching { subject.invalidateAndRefresh("initial-token") } } }
            while (calls.get() == 0) yield()
            gate.complete(Unit)

            val outcomes = waiters.map { it.await().exceptionOrNull() }
            assertEquals("exactly one provider invocation", 1, calls.get())
            for (outcome in outcomes) {
                assertTrue("got $outcome", outcome is PayabliException)
                assertEquals(PayabliErrorCode.TOKEN_EXPIRED, (outcome as PayabliException).code)
            }
        }

    @Test
    fun `a failed refresh does not wedge the next attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val subject =
                auth {
                    if (calls.incrementAndGet() == 1) throw IOException("first attempt fails") else "recovered"
                }

            failureFrom { subject.invalidateAndRefresh("initial-token") }
            assertEquals("recovered", completing("the retry") { subject.invalidateAndRefresh("initial-token") })
            assertEquals("recovered", subject.accessToken())
        }

    @Test
    fun `a provider that reads the token does not deadlock`() =
        runTest(timeout = TEST_TIMEOUT) {
            var holder: PayabliAuth? = null
            var reentrantRead: String? = null
            val subject =
                auth {
                    reentrantRead = holder!!.accessToken()
                    "fresh-token"
                }
            holder = subject

            assertEquals("fresh-token", completing("the refresh") { subject.invalidateAndRefresh("initial-token") })
            // Observed directly rather than through the outer result: a re-entrant caller cannot wait for
            // itself, so it is served the last known token.
            assertEquals("initial-token", reentrantRead)
        }

    @Test
    fun `a provider that refreshes again does not deadlock`() =
        runTest(timeout = TEST_TIMEOUT) {
            var holder: PayabliAuth? = null
            var reentrantRefresh: String? = null
            val subject =
                auth {
                    reentrantRefresh = holder!!.invalidateAndRefresh("initial-token")
                    "fresh-token"
                }
            holder = subject

            assertEquals("fresh-token", completing("the refresh") { subject.invalidateAndRefresh("initial-token") })
            assertEquals("initial-token", reentrantRefresh)
        }

    @Test
    fun `a provider that never returns fails on the deadline and frees the claim`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val subject =
                auth {
                    if (calls.incrementAndGet() == 1) CompletableDeferred<String>().await() else "recovered"
                }

            val failure = failureFrom { subject.invalidateAndRefresh("initial-token") }
            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)

            // The claim was released, so the next attempt is not wedged behind the stuck one.
            assertEquals(
                "recovered",
                completing("the attempt after a timeout") { subject.invalidateAndRefresh("initial-token") },
            )
        }

    @Test
    fun `a cancelled initiator gives waiters a token failure, not its cancellation`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val subject =
                auth {
                    calls.incrementAndGet()
                    entered.complete(Unit)
                    CompletableDeferred<String>().await()
                }

            val initiator = async { subject.invalidateAndRefresh("initial-token") }
            entered.await()
            val waiter = async { runCatching { subject.accessToken() } }
            yield()
            initiator.cancel()

            val outcome = waiter.await().exceptionOrNull()
            assertTrue("got $outcome", outcome is PayabliException)
            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, (outcome as PayabliException).code)
            assertEquals("exactly one provider invocation", 1, calls.get())
        }

    @Test
    fun `a provider error carrying another code still surfaces as token expired`() =
        runTest(timeout = TEST_TIMEOUT) {
            val subject =
                auth {
                    throw PayabliGenericException(PayabliErrorCode.NETWORK_ERROR, "provider used our own type")
                }

            val failure = failureFrom { subject.invalidateAndRefresh("initial-token") }
            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)
        }

    @Test
    fun `a read during a refresh returns the fresh token`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gate = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val subject =
                auth {
                    calls.incrementAndGet()
                    gate.await()
                    "fresh-token"
                }

            val refresh = async { subject.invalidateAndRefresh("initial-token") }
            while (calls.get() == 0) yield()
            val read = async { subject.accessToken() }
            yield()
            gate.complete(Unit)

            assertEquals("fresh-token", refresh.await())
            assertEquals("the read waited for the rotation", "fresh-token", read.await())
        }

    @Test
    fun `a staggered rejection on an already-rotated token does not refresh again`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val subject = auth { "T${calls.incrementAndGet()}" }

            // Request A was rejected on the initial token and rotates it.
            val first = subject.invalidateAndRefresh("initial-token")
            // Request B's rejection for that same token arrives afterwards.
            val second = subject.invalidateAndRefresh("initial-token")

            assertEquals("T1", first)
            assertEquals("B is handed the rotation A obtained", "T1", second)
            assertEquals("one provider invocation, not two", 1, calls.get())
            assertEquals("T1", subject.accessToken())
        }

    @Test
    fun `a rejection on the current token still refreshes`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val subject = auth { "T${calls.incrementAndGet()}" }

            assertEquals("T1", subject.invalidateAndRefresh("initial-token"))
            // Rejected on what is now current, so this is a genuine second rotation.
            assertEquals("T2", subject.invalidateAndRefresh("T1"))
            assertEquals(2, calls.get())
        }

    @Test
    fun `a blank refreshed token is refused and the old one survives`() =
        runTest(timeout = TEST_TIMEOUT) {
            val subject = auth { "   " }

            val failure = failureFrom { subject.invalidateAndRefresh("initial-token") }

            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)
            assertEquals("the usable token is untouched", "initial-token", subject.accessToken())
        }

    /**
     * A provider handing back a cached value is the realistic way this happens. Committing it would
     * publish a rotation that did not occur and return a credential the server already refused, and since
     * the current token would be unchanged, each later rejection starts another provider call.
     */
    @Test
    fun `a refreshed token identical to the rejected one is refused`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val subject = auth { "initial-token".also { calls.incrementAndGet() } }
            val seen = mutableListOf<String>()
            val collector = launch { subject.tokenChanges.collect { seen += it } }
            yield()

            val failure = failureFrom { subject.invalidateAndRefresh("initial-token") }

            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)
            assertEquals("the provider is called once, not once per rejection", 1, calls.get())
            assertEquals("no rotation happened, so none is published", emptyList<String>(), seen)
            collector.cancel()
        }

    @Test
    fun `a provider throwing our own token-expired type is still redacted`() =
        runTest(timeout = TEST_TIMEOUT) {
            val sentinel = "SENTINEL-BACKEND-DETAIL"
            val subject =
                auth {
                    throw PayabliGenericException(
                        PayabliErrorCode.TOKEN_EXPIRED,
                        "backend said $sentinel",
                        detail = sentinel,
                    )
                }

            val failure = failureFrom { subject.invalidateAndRefresh("initial-token") }

            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)
            assertFalse("the provider's reason reached the caller", failure.reason.contains(sentinel))
            assertFalse("it leaked through the chain", failure.stackTraceToString().contains(sentinel))
        }

    @Test
    fun `a stale rejection joins a refresh of the token that replaced it`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gate = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val subject =
                auth {
                    val n = calls.incrementAndGet()
                    if (n > 1) gate.await()
                    "T$n"
                }

            // T1 is current, and is itself rejected, so a refresh to T2 is in flight.
            assertEquals("T1", subject.invalidateAndRefresh("initial-token"))
            val pending = async { subject.invalidateAndRefresh("T1") }
            while (calls.get() < 2) yield()

            // A long-delayed rejection of the original token must not be handed T1, which is being replaced.
            val stale = async { subject.invalidateAndRefresh("initial-token") }
            yield()
            gate.complete(Unit)

            assertEquals("T2", pending.await())
            assertEquals("the stale caller joined the pending refresh", "T2", stale.await())
            assertEquals("no extra provider call", 2, calls.get())
        }

    @Test
    fun `a provider raising cancellation of its own is a provider failure`() =
        runTest(timeout = TEST_TIMEOUT) {
            val subject = auth { throw CancellationException("the provider's own nested timeout") }

            // Our caller was never cancelled, so this must not masquerade as caller cancellation.
            val failure = failureFrom { subject.invalidateAndRefresh("initial-token") }

            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)
            assertEquals("token refresh failed", failure.reason)
        }

    @Test
    fun `a timeout reports the deadline, not a generic failure`() =
        runTest(timeout = TEST_TIMEOUT) {
            val subject =
                PayabliAuth(
                    PayabliConfig(
                        accessToken = "initial-token",
                        entryPoint = "entry",
                        environment = PayabliEnvironment.SANDBOX,
                        tokenProvider = { CompletableDeferred<String>().await() },
                    ),
                    DefaultPayabliLogger(LogCategory.AUTH, sink),
                    providerTimeoutMillis = 50,
                )

            val failure = failureFrom { subject.invalidateAndRefresh("initial-token") }

            assertEquals("the tokenProvider did not return in time", failure.reason)
        }

    @Test
    fun `a cancelled refresh leaves the holder usable`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val subject =
                auth {
                    if (calls.incrementAndGet() == 1) {
                        entered.complete(Unit)
                        CompletableDeferred<String>().await()
                    } else {
                        "recovered"
                    }
                }

            val initiator = async { subject.invalidateAndRefresh("initial-token") }
            entered.await()
            // cancelAndJoin, not cancel: a caller arriving mid-cancellation correctly shares the dying
            // claim's outcome, so asserting liveness means waiting for the cleanup to finish first.
            initiator.cancelAndJoin()

            // The liveness property the NonCancellable cleanup exists for: the claim was released, so the
            // holder still works rather than wedging every later caller on an abandoned deferred.
            assertEquals(
                "recovered",
                completing("the refresh after cancellation") {
                    subject.invalidateAndRefresh("initial-token")
                },
            )
            assertEquals("recovered", subject.accessToken())
        }

    @Test
    fun `a fatal error reaches the caller and still frees the claim`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val subject =
                auth {
                    if (calls.incrementAndGet() == 1) throw OutOfMemoryError("not a refresh problem") else "recovered"
                }

            val thrown = runCatching { subject.invalidateAndRefresh("initial-token") }.exceptionOrNull()
            assertTrue("got $thrown", thrown is OutOfMemoryError)

            // Letting the Error through must not strand the claim, or every later caller waits forever.
            assertEquals(
                "recovered",
                completing("the refresh after a fatal error") {
                    subject.invalidateAndRefresh("initial-token")
                },
            )
        }

    /**
     * The two `NonCancellable` guards in the holder were unprotected: both could be deleted with every
     * other test still green. Neither can be reached by an uncontended lock, because a cancellable suspend
     * function only observes cancellation at a suspension point and acquiring a free mutex is not one.
     *
     * So the test holds the lock itself, forcing the cleanup to suspend, and cancels only once the refresh
     * is waiting there. Order matters: cancelling any earlier cancels the provider's own await instead and
     * exercises the cancellation branch, which is a different path.
     */
    @Test
    fun `a cancelled commit waits for a contended lock and still releases the claim`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            val subject =
                auth {
                    if (calls.incrementAndGet() == 1) {
                        gate.await()
                        "fresh-token"
                    } else {
                        "recovered"
                    }
                }

            val job = launch { runCatching { subject.invalidateAndRefresh("initial-token") } }
            while (calls.get() == 0) yield()

            subject.mutex.lock()
            gate.complete(Unit)
            // One yield is enough on the test dispatcher: the provider's continuation runs to the
            // refresh's next suspension point, which is the lock the test is holding.
            yield()

            job.cancel()
            subject.mutex.unlock()
            job.join()

            // Rejecting what the commit installed, so this is a genuine second refresh rather than the
            // already-rotated shortcut. A stranded claim makes it join a deferred nobody will complete.
            assertEquals(
                "recovered",
                completing("a refresh after a cancelled commit") {
                    subject.invalidateAndRefresh("fresh-token")
                },
            )
        }

    @Test
    fun `a cancelled failure cleanup waits for a contended lock and still releases the claim`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            val subject =
                auth {
                    if (calls.incrementAndGet() == 1) {
                        gate.await()
                        throw IOException("the provider failed")
                    } else {
                        "recovered"
                    }
                }

            val job = launch { runCatching { subject.invalidateAndRefresh("initial-token") } }
            while (calls.get() == 0) yield()

            subject.mutex.lock()
            gate.complete(Unit)
            // One yield is enough on the test dispatcher: the provider's continuation runs to the
            // refresh's next suspension point, which is the lock the test is holding.
            yield()

            job.cancel()
            subject.mutex.unlock()
            job.join()

            assertEquals(
                "recovered",
                completing("a refresh after a cancelled failure cleanup") {
                    subject.invalidateAndRefresh("initial-token")
                },
            )
        }

    @Test
    fun `a non-positive provider deadline is refused at construction`() =
        runTest(timeout = TEST_TIMEOUT) {
            for (invalid in listOf(0L, -1L)) {
                val thrown =
                    runCatching {
                        PayabliAuth(
                            PayabliConfig("t", "e", PayabliEnvironment.SANDBOX),
                            DefaultPayabliLogger(LogCategory.AUTH, sink),
                            providerTimeoutMillis = invalid,
                        )
                    }.exceptionOrNull()
                assertTrue("$invalid should be refused, got $thrown", thrown is IllegalArgumentException)
            }
        }

    @Test
    fun `the log records the refresh without the token`() =
        runTest(timeout = TEST_TIMEOUT) {
            val subject = auth { "SENTINEL-FRESH-TOKEN" }

            subject.invalidateAndRefresh("initial-token")

            val logged = sink.records.joinToString("\n") { it.message }
            assertTrue(logged.contains("token_refreshed"))
            assertFalse("the token was logged", logged.contains("SENTINEL-FRESH-TOKEN"))
        }
}
