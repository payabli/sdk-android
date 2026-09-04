@file:OptIn(ExperimentalCoroutinesApi::class)

package com.payabli.sdk.core.auth

import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.config.PayabliTokenProvider
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds
private val COMPLETION_TIMEOUT = 2.seconds
private const val TERMINAL_REASON = "the refreshed token was rejected as well"
private const val THREE_CALLERS = 3

/**
 * Scheduler turns given to a coroutine that must **not** produce an answer. Bounded, since the assertion
 * is that nothing happens and an unbounded wait for nothing is a hang.
 */
private const val SCHEDULER_TURNS = 20

/** The 2.2 auth holder: refresh de-duplication, the change flow, and how a provider failure surfaces. */
class PayabliAuthTest {
    private val sink = RecordingLogSink()

    /** The failure the choke-point hands in when a refreshed token is refused again. */
    private fun terminal() = PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, TERMINAL_REASON)

    /**
     * A provider that answers each value in turn and the last one for every call after.
     *
     * The holder mints rather than being handed a token, so a test that needs one value held and a
     * different one on refresh says both here instead of seeding the first.
     */
    private fun answering(vararg tokens: String): PayabliTokenProvider {
        val calls = AtomicInteger()
        return PayabliTokenProvider { tokens[minOf(calls.getAndIncrement(), tokens.lastIndex)] }
    }

    /**
     * A holder already holding [held], whose provider runs [refresh] for every call after that.
     *
     * Where a test about a refresh starts: a rejection needs a token to have been sent, and the holder
     * obtains its first one by minting rather than by being handed it. The read is a provider call and a
     * test that counts calls counts it, which is not an artefact of this helper. Obtaining the first token
     * is a call to the host's backend, and a count that omitted it would describe a holder that got its
     * first token from somewhere else.
     */
    private suspend fun holding(
        held: String,
        refresh: PayabliTokenProvider,
    ): PayabliAuth {
        val calls = AtomicInteger()
        return auth { if (calls.getAndIncrement() == 0) held else refresh.freshToken() }
            .also { it.accessToken() }
    }

    private fun auth(tokenProvider: PayabliTokenProvider = PayabliTokenProvider { "initial-token" }) =
        PayabliAuth(
            PayabliConfig(
                entryPoint = "entry",
                environment = PayabliEnvironment.SANDBOX,
                tokenProvider = tokenProvider,
            ),
            DefaultSdkLogger(LogCategory.AUTH, sink),
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
    fun `the first token comes from the provider, on the first read`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val subject = auth { "minted-${calls.incrementAndGet()}" }

            // Nothing is minted by construction: the holder is handed no token, so the read is what obtains
            // one. A holder built and never read costs the host's backend nothing.
            assertEquals("constructing the holder must not call the provider", 0, calls.get())

            assertEquals("minted-1", subject.accessToken())
            assertEquals(1, calls.get())
            assertEquals("a second read reuses what was minted", "minted-1", subject.accessToken())
            assertEquals(1, calls.get())
        }

    /**
     * The change flow reports rotations, and a first mint replaces nothing. A collector subscribes to be
     * told when the token it holds stopped being the current one, so reporting the first would make every
     * session's first request read as a refresh.
     */
    @Test
    fun `the first mint is not published as a rotation`() =
        runTest(timeout = TEST_TIMEOUT) {
            val subject = holding("initial-token") { "fresh-token" }
            val seen = mutableListOf<String>()
            val collector = launch { subject.tokenChanges.collect { seen += it } }
            yield()

            assertEquals("initial-token", subject.accessToken())
            yield()
            assertEquals("nothing rotated, so nothing is published", emptyList<String>(), seen)

            subject.invalidateAndRefresh("initial-token")
            yield()

            assertEquals(listOf("fresh-token"), seen)
            collector.cancel()
        }

    @Test
    fun `concurrent first readers share one mint`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            val subject =
                auth {
                    calls.incrementAndGet()
                    gate.await()
                    "minted-once"
                }

            val readers = List(5) { async { subject.accessToken() } }
            while (calls.get() == 0) yield()
            gate.complete(Unit)

            // The claim a refresh takes is the claim a first read takes, which is what keeps five readers
            // from becoming five calls to the host's backend on a cold session.
            assertEquals(List(5) { "minted-once" }, readers.map { it.await() })
            assertEquals("exactly one provider invocation", 1, calls.get())
        }

    /**
     * A provider that reads the token while minting the first one is asking for the value it was called to
     * produce. There is nothing to answer it with, and joining the claim would be joining itself, so it
     * fails instead of waiting.
     *
     * Bounded: the failure this rules out is a wedge, and an unbounded case would hang with no output.
     */
    @Test
    fun `a provider that reads the token it is minting fails rather than waiting`() =
        runTest(timeout = TEST_TIMEOUT) {
            var holder: PayabliAuth? = null
            var reentrant: Result<String>? = null
            val subject =
                auth {
                    reentrant = runCatching { holder!!.accessToken() }
                    "fresh-token"
                }
            holder = subject

            assertEquals("fresh-token", completing("the first mint") { subject.accessToken() })

            val failure = reentrant?.exceptionOrNull()
            assertTrue("expected a PayabliException, got $failure", failure is PayabliException)
            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, (failure as PayabliException).code)
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
                holding("initial-token") {
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
                holding("initial-token") {
                    reentrantRefresh = holder!!.invalidateAndRefresh("initial-token")
                    "fresh-token"
                }
            holder = subject

            assertEquals("fresh-token", completing("the refresh") { subject.invalidateAndRefresh("initial-token") })
            assertEquals("initial-token", reentrantRefresh)
        }

    @Test
    fun `two holders whose providers call each other both complete`() =
        runTest(timeout = TEST_TIMEOUT) {
            var outer: PayabliAuth? = null
            var backIntoTheOuter: String? = null
            val inner =
                holding("second-old") {
                    backIntoTheOuter = outer!!.invalidateAndRefresh("first-old")
                    "second-fresh"
                }
            val subject =
                holding("first-old") {
                    inner.invalidateAndRefresh("second-old")
                    "first-fresh"
                }
            outer = subject

            assertEquals("first-fresh", completing("the refresh") { subject.invalidateAndRefresh("first-old") })
            // Answered rather than joined: the refresh this call would have awaited is the one waiting on it.
            assertEquals("first-old", backIntoTheOuter)
            assertEquals("first-fresh", subject.accessToken())
        }

    @Test
    fun `a provider calling an unrelated holder refreshes that holder`() =
        runTest(timeout = TEST_TIMEOUT) {
            var fromTheOtherHolder: String? = null
            val other = holding("other-old") { "other-fresh" }
            val subject =
                auth {
                    fromTheOtherHolder = other.invalidateAndRefresh("other-old")
                    "fresh-token"
                }

            assertEquals("fresh-token", completing("the refresh") { subject.invalidateAndRefresh("initial-token") })
            // A mark belongs to the holder that set it, so an unrelated holder rotates instead of
            // answering with what it already had.
            assertEquals("other-fresh", fromTheOtherHolder)
        }

    /**
     * A scope built from the calling context carries the mark, and the new `Job` severs it from the
     * refresh that set it, so the mark can outlive that refresh. It must not answer for a later one.
     */
    @Test
    fun `a mark carried past its own refresh joins the refresh in flight`() =
        runTest(timeout = TEST_TIMEOUT) {
            var holder: PayabliAuth? = null
            val calls = AtomicInteger()
            val escapedIsAtTheCall = CompletableDeferred<Unit>()
            val releaseEscaped = CompletableDeferred<Unit>()
            val secondProviderCall = CompletableDeferred<Unit>()
            var escaped: Job? = null
            var escapedAnswer: String? = null

            val releaseSecond = CompletableDeferred<Unit>()
            val subject =
                auth {
                    when (calls.incrementAndGet()) {
                        1 -> {
                            escaped =
                                CoroutineScope(currentCoroutineContext() + Job()).launch {
                                    escapedIsAtTheCall.complete(Unit)
                                    releaseEscaped.await()
                                    escapedAnswer = holder!!.invalidateAndRefresh("token-1")
                                }
                            escapedIsAtTheCall.await()
                            "token-1"
                        }
                        else -> {
                            secondProviderCall.complete(Unit)
                            releaseSecond.await()
                            "token-2"
                        }
                    }
                }
            holder = subject

            assertEquals("token-1", completing("the first refresh") { subject.invalidateAndRefresh("initial-token") })

            // The window this case is about: a later refresh is in flight and parked in the provider, so a
            // mark left over from the finished one would be taken for a live mark.
            val later = async { subject.invalidateAndRefresh("token-1") }
            secondProviderCall.await()
            releaseEscaped.complete(Unit)
            repeat(SCHEDULER_TURNS) { yield() }
            assertNull("a mark from a finished refresh answered a later rejection", escapedAnswer)

            releaseSecond.complete(Unit)
            escaped!!.join()
            assertEquals("token-2", later.await())
            assertEquals("token-2", escapedAnswer)
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
            val subject = holding("initial-token") { "   " }

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

    /**
     * A refreshed token that cannot be a header value is refused before it is installed.
     *
     * Left unchecked it reaches `setRequestProperty`, which throws an unchecked `IllegalArgumentException`
     * from inside the transport, so the caller sees the wrong type and the transport contract that
     * implementations throw `PayabliException` is broken. A CR or LF is also header injection.
     */
    @Test
    fun `a refreshed token that cannot be a header value is refused`() =
        runTest(timeout = TEST_TIMEOUT) {
            for (bad in listOf("fresh\rtoken", "fresh\ntoken", "fresh\u0000token")) {
                val subject = holding("initial-token") { bad }

                val failure = failureFrom { subject.invalidateAndRefresh("initial-token") }

                assertEquals("$bad should be malformed", PayabliErrorCode.TOKEN_MALFORMED, failure.code)
                assertEquals("the usable token is untouched", "initial-token", subject.accessToken())
            }
        }

    /**
     * The header-safety check must not be so strict it refuses tokens a real broker mints.
     *
     * Moved here from the configuration's own tests when the seed was removed: the check lives on the
     * refresh path now, and a rule with only negative cases can be tightened until it rejects everything
     * without anything going red.
     */
    @Test
    fun `an ordinary bearer credential survives the refresh check`() =
        runTest(timeout = TEST_TIMEOUT) {
            for (good in listOf("abcDEF123", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig", "a-b_c.d~e=", "tok en")) {
                val subject = auth(answering("initial-token", good))
                assertEquals("initial-token", subject.accessToken())

                assertEquals(good, subject.invalidateAndRefresh("initial-token"))
            }
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

            // The caller was never cancelled, so this must not masquerade as caller cancellation.
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
                        entryPoint = "entry",
                        environment = PayabliEnvironment.SANDBOX,
                        tokenProvider = { CompletableDeferred<String>().await() },
                    ),
                    DefaultSdkLogger(LogCategory.AUTH, sink),
                    providerTimeoutMillis = 50,
                )

            val failure = failureFrom { subject.invalidateAndRefresh("initial-token") }

            assertEquals("the tokenProvider did not return in time", failure.reason)
        }

    /**
     * The shipped deadline, pinned in the declaration and on the clock.
     *
     * Two assertions, and each catches something the other does not. The literal on the expected side is what
     * fails when the constant moves, in either direction; comparing the constant to itself would pass against
     * any value. The elapsed virtual time is what makes that literal a fact about behaviour rather than about
     * a declaration: it is what a parked provider actually buys before the refresh reports expiry, so a
     * deadline that stopped being applied fails here too.
     *
     * Costs no wall clock. `runRefresh` never switches dispatcher, so `withTimeoutOrNull` runs on the test
     * scheduler and thirty virtual seconds elapse at once. Not wrapped in [completing], whose two-second
     * bound is virtual as well and would fire first.
     */
    @Test
    fun `the shipped provider deadline is thirty seconds, in the constant and on the clock`() =
        runTest(timeout = TEST_TIMEOUT) {
            assertEquals("the shipped provider deadline moved", 30_000L, DEFAULT_PROVIDER_TIMEOUT_MILLIS)

            // No providerTimeoutMillis: this is the default in the shape production uses it.
            val subject = auth { CompletableDeferred<String>().await() }
            val startedAt = currentTime

            val failure = failureFrom { subject.invalidateAndRefresh("initial-token") }

            assertEquals("the tokenProvider did not return in time", failure.reason)
            assertEquals("the deadline was not what ended it", 30_000L, currentTime - startedAt)
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
                            PayabliConfig("e", PayabliEnvironment.SANDBOX, PayabliTokenProvider { "t" }),
                            DefaultSdkLogger(LogCategory.AUTH, sink),
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

    @Test
    fun `a token being refreshed right now is not settled, even though it is still current`() =
        runTest(timeout = TEST_TIMEOUT) {
            val claimed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val subject =
                auth(
                    tokenProvider = {
                        claimed.complete(Unit)
                        release.await()
                        "refreshed-token"
                    },
                )

            val refreshing = launch { subject.invalidateAndRefresh("initial-token") }
            completing("the refresh to claim") {
                claimed.await()
                "claimed"
            }

            // A claim is taken before the provider runs and the new token is written only when it commits,
            // so right now the old token is still the current one. Reading currency alone would report
            // settled and let a caller end a session over a credential already being replaced.
            assertFalse(
                "a token with a refresh in flight must not be reported as settled",
                subject.finishIfSettledOn("initial-token", terminal()),
            )

            release.complete(Unit)
            refreshing.join()

            // Checked before finishing anything, or it would pass for the wrong reason.
            assertFalse(
                "the replaced token is not settled once the refresh has committed",
                subject.finishIfSettledOn("initial-token", terminal()),
            )
            assertTrue(
                "the token the refresh minted is settled",
                subject.finishIfSettledOn("refreshed-token", terminal()),
            )
        }

    @Test
    fun `a finished instance refuses a refresh and never calls the provider`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val subject =
                holding("initial-token") {
                    calls.incrementAndGet()
                    "never-minted"
                }
            assertTrue(subject.finishIfSettledOn("initial-token", terminal()))

            val failure = failureFrom { subject.invalidateAndRefresh("initial-token") }

            // The guarantee the whole arrangement exists for: the claim is the only way to the provider, and
            // a finished instance never takes one, whatever stage a caller had already reached elsewhere.
            assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)
            assertEquals("the host's broker must not be called on a finished instance", 0, calls.get())
            assertEquals(TERMINAL_REASON, failure.reason)
        }

    @Test
    fun `every concurrent caller on a finished instance gets the same refusal`() =
        runTest(timeout = TEST_TIMEOUT) {
            val calls = AtomicInteger()
            val subject =
                holding("initial-token") {
                    calls.incrementAndGet()
                    "never-minted"
                }
            assertTrue(subject.finishIfSettledOn("initial-token", terminal()))

            val outcomes =
                (1..THREE_CALLERS)
                    .map { async { runCatching { subject.invalidateAndRefresh("initial-token") } } }
                    .map { it.await() }

            // One entry, one exit. Nobody starts a refresh and nobody waits on one that will not come.
            assertTrue("every caller should have been refused", outcomes.all { it.isFailure })
            outcomes.forEach {
                assertEquals(TERMINAL_REASON, (it.exceptionOrNull() as PayabliException).reason)
            }
            assertEquals("no caller reached the provider", 0, calls.get())
        }

    @Test
    fun `reset revives a finished instance, so one test cannot poison the next`() =
        runTest(timeout = TEST_TIMEOUT) {
            val subject = holding("initial-token") { "refreshed-token" }
            assertTrue(subject.finishIfSettledOn("initial-token", terminal()))
            assertNotNull(subject.terminalFailure)

            subject.reset()

            assertNull("reset must clear the terminal failure", subject.terminalFailure)
            assertEquals(
                "a revived instance refreshes normally",
                "refreshed-token",
                completing("the refresh after reset") { subject.invalidateAndRefresh("initial-token") },
            )
        }
}
