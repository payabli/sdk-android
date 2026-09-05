package com.payabli.sdk.core

import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.config.PayabliTokenProvider
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.TransportFactory
import com.payabli.sdk.core.network.impl.AuthFailureListener
import com.payabli.sdk.testutils.auth.mintingThen
import com.payabli.sdk.testutils.network.LoopbackServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 10.seconds
private val COMPLETION_TIMEOUT = 5.seconds

/** Long enough that a caller which is not blocked would have finished; short enough to stay a test. */
private val BLOCKED_PROBE = 300.milliseconds
private const val AUTHORIZATION = "Authorization"
private const val STALE = "stale-token"
private const val FRESH = "fresh-token"

/**
 * The one session, and what it is for.
 *
 * The pieces underneath already worked before this type existed. What did not work was sharing them: the
 * factory built a token holder per call and could only ask callers to build one and reuse it, so two
 * capabilities in one app quietly ran two refresh domains. One session serves every capability, never two,
 * and these assert that requirement rather than the mechanics of any one piece.
 *
 * The contrast test is deliberate. Proving the session shares a refresh means little without showing that
 * the arrangement it replaces does not.
 */
class PayabliSessionTest {
    private val providerCalls = AtomicInteger()

    @After
    fun restoreProcessWideState() {
        // Process-wide, like the log cutoff: a session left installed decides the outcome of the next class.
        // Bounded, because reset takes the same lock initialize does: a test that left it held would
        // otherwise hang the whole suite here rather than report the failure that caused it.
        runBlocking {
            assertNotNull(
                "could not clear the installed session; a test left the initialize lock held",
                withTimeoutOrNull(COMPLETION_TIMEOUT) { PayabliSession.reset() },
            )
        }

        // Asserted, so a leak fails in the class that caused it. The published state is the only place a
        // caller can read Uninitialized, so a class that leaves it set makes the test proving that fail
        // somewhere else entirely, with nothing pointing back here.
        assertEquals(
            "left the SDK's published state set for every later test class in this JVM",
            SdkState.Uninitialized,
            PayabliSession.state.value,
        )
    }

    private fun provider(token: String = FRESH) =
        PayabliTokenProvider {
            providerCalls.incrementAndGet()
            token
        }

    /**
     * [STALE] is what the holder mints first, so a test drives the shape it is about: a request carrying
     * the token the service refuses, then a refresh through [tokenProvider]. Counting inside that provider
     * therefore counts refreshes and not the first mint.
     */
    private fun config(
        entryPoint: String = "entry",
        tokenProvider: PayabliTokenProvider = provider(),
    ) = PayabliConfig(
        entryPoint = entryPoint,
        environment = PayabliEnvironment.SANDBOX,
        tokenProvider = mintingThen(STALE, tokenProvider),
    )

    /** Answers 401 to the stale token and 200 to anything else, which is the whole shape of a refresh. */
    private fun rejectingStale(server: LoopbackServer) =
        server.respondPerRequest { recorded ->
            if (recorded.header(AUTHORIZATION) == "Bearer $STALE") 401 to "{}" else 200 to "{}"
        }

    private fun ping() = PayabliRequest(HttpMethod.GET, "/api/ping", route = "/api/ping")

    /**
     * Runs blocking socket work off the test scheduler and bounds it, so a wedge names itself here instead
     * of running out `runTest`'s own timeout with a message that names nothing.
     */
    private suspend fun <T : Any> completing(
        what: String,
        block: suspend () -> T,
    ): T =
        withContext(Dispatchers.IO) { withTimeoutOrNull(COMPLETION_TIMEOUT) { block() } }
            ?: throw AssertionError("$what never completed")

    private suspend fun session(
        server: LoopbackServer,
        config: PayabliConfig,
    ): PayabliSession = PayabliSession.initializeAgainst(server.baseUrl, config).getOrThrow()

    /**
     * Its own configuration rather than [config], whose first answer comes from the wrapper and would make
     * a count of [provider] read zero whether a mint happened or not.
     */
    @Test
    fun `initialize mints nothing, so a session nobody uses costs the host's backend nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                val calls = AtomicInteger()
                session(
                    server,
                    PayabliConfig(
                        entryPoint = "entry",
                        environment = PayabliEnvironment.SANDBOX,
                        tokenProvider = {
                            calls.incrementAndGet()
                            STALE
                        },
                    ),
                )

                // The token is obtained on the first request instead. A session that is initialized and
                // never used spends no call on the host's backend, and one left idle does not hold a token
                // that expired before anything sent it.
                assertEquals("initialize must not call the provider", 0, calls.get())
            }
        }

    // ---- the acceptance criterion -------------------------------------------------------------------

    @Test
    fun `two consumers of one session share a single refresh`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                rejectingStale(server)
                val subject = session(server, config(tokenProvider = provider()))

                // Two capability facades, each holding the session and issuing its own request. Both are
                // rejected on the stale token, so both reach for a refresh at the same time.
                val first = async { completing("the first consumer's request") { subject.transport.execute(ping()) } }
                val second = async { completing("the second consumer's request") { subject.transport.execute(ping()) } }

                assertEquals(200, first.await().statusCode)
                assertEquals(200, second.await().statusCode)

                // The whole point of one session. Two holders would each see their own 401 as the first one
                // and call the host's broker twice for a single expiry.
                assertEquals("the shared holder should have refreshed once for both consumers", 1, providerCalls.get())
            }
        }

    @Test
    fun `two separately built transports do not share a refresh`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                rejectingStale(server)
                val config = config(tokenProvider = provider())

                // The arrangement the session replaces: each caller assembling its own auth stack.
                val first: PayabliTransport =
                    TransportFactory.authenticatedAgainst(
                        server.baseUrl,
                        config,
                        Dispatchers.IO,
                    )
                val second: PayabliTransport =
                    TransportFactory.authenticatedAgainst(
                        server.baseUrl,
                        config,
                        Dispatchers.IO,
                    )

                completing("the first transport's request") { first.execute(ping()) }
                completing("the second transport's request") { second.execute(ping()) }

                // Not a defect in the factory, which is doing exactly what it is asked. It is why asking is
                // not enough, and why the session owns the holder now.
                assertEquals("two holders should each have refreshed on their own", 2, providerCalls.get())
            }
        }

    // ---- initialize ---------------------------------------------------------------------------------

    @Test
    fun `initialize is idempotent for an equal configuration`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                val first = session(server, config(tokenProvider = provider()))

                // A freshly built, equal configuration, which is what a host writing the call twice produces.
                // Comparing by object identity would fail here and hand back a second session.
                val second = session(server, config(tokenProvider = provider()))

                assertSame("a second initialize with the same configuration must not build a session", first, second)
            }
        }

    /**
     * `runBlocking`, where the rest of this file uses `runTest`.
     *
     * This is the one test here about real threads interleaving, and `runTest`'s virtual clock is the wrong
     * clock for it: the blocked-probe below never elapses in virtual time while a real coroutine is parked
     * on a real `Mutex`, and the measured cost of trying was sixteen minutes for a class that otherwise runs
     * in under a second. Real dispatchers, real deadline, bounded so it fails fast rather than wedging.
     */
    @Test
    fun `a second caller waits while the first is inside initialize`() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                LoopbackServer().use { server ->
                    val insideFirst = CompletableDeferred<Unit>()
                    val releaseFirst = CompletableDeferred<Unit>()
                    try {
                        // The critical section is a few microseconds of object construction, so two callers
                        // launched together almost never collide and a test that just races them passes
                        // with the lock removed. Holding the section open is what makes the second caller's
                        // wait observable.
                        val first =
                            async(Dispatchers.IO) {
                                PayabliSession.initializeWith(config()) { _ ->
                                    insideFirst.complete(Unit)
                                    releaseFirst.await()
                                    TransportFactory.authenticatedAgainst(server.baseUrl, config(), Dispatchers.IO)
                                }
                            }
                        insideFirst.await()

                        val second =
                            async(Dispatchers.IO) {
                                PayabliSession.initializeWith(config()) { _ ->
                                    TransportFactory.authenticatedAgainst(server.baseUrl, config(), Dispatchers.IO)
                                }
                            }

                        // Still blocked: without the mutex it finds no installed session and builds a second
                        // one, which is the two-sessions state this whole type exists to prevent.
                        assertNull(
                            "the second caller entered initialize while the first was still inside",
                            withTimeoutOrNull(BLOCKED_PROBE) { second.await() },
                        )

                        releaseFirst.complete(Unit)
                        assertSame(
                            "two callers racing initialize must not install two sessions",
                            first.await().getOrThrow(),
                            second.await().getOrThrow(),
                        )
                    } finally {
                        // An assertion failing above must not leave the lock held, or every later test in
                        // this class waits on it and the suite reports a wedge instead of the failure.
                        releaseFirst.complete(Unit)
                    }
                }
            }
        }

    @Test
    fun `initialize refuses a different configuration while the session is usable`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                val first = session(server, config())

                val second = PayabliSession.initializeAgainst(server.baseUrl, config(entryPoint = "other"))

                // Refused rather than replaced. Replacing would leave capabilities holding the old session
                // while new ones got the new one, which is the two-sessions state this type exists to stop.
                val failure = second.exceptionOrNull()
                assertTrue("expected a failure, got $second", failure is PayabliException)
                assertEquals(
                    PayabliErrorCode.INVALID_CONFIGURATION,
                    (failure as PayabliException).code,
                )
                // Survived rather than merely "not replaced": asking again with the original configuration
                // hands back the same instance, which a refusal that had torn anything down could not do.
                assertSame("the usable session must survive a refused re-initialize", first, session(server, config()))
            }
        }

    @Test
    fun `initialize replaces a session that requires re-initialization`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondWith(401, "{}")
                val dead = session(server, config())
                runCatching { completing("the request that finishes the session") { dead.transport.execute(ping()) } }
                assertEquals(SdkState.ReinitializeRequired, PayabliSession.state.value)

                // The documented recovery. Refusing here would leave the host with no way back, and the
                // token the provider mints for the new session is not what makes it a new one: the identity
                // never included a credential.
                val revived = session(server, config())

                assertNotSame("re-initializing after a finished session must build a new one", dead, revived)
                assertEquals(SdkState.Ready, PayabliSession.state.value)

                // The state is one value for the process, so it stops answering for the session that was
                // replaced. What answers for that one is its transport, and it is still finished: a holder
                // that missed the replacement cannot get a request out of it.
                val throughDead =
                    runCatching {
                        completing("the request through the replaced session") { dead.transport.execute(ping()) }
                    }.exceptionOrNull()
                assertEquals(PayabliErrorCode.TOKEN_EXPIRED, (throughDead as PayabliException).code)
            }
        }

    // ---- state --------------------------------------------------------------------------------------

    /**
     * The acceptance criterion: a caller reads [SdkState.Uninitialized] without holding a session.
     *
     * A collector across the transition rather than two reads of `value`, which a constant would satisfy just
     * as well. Nothing in the shipped SDK transitions back **into** the pre-initialized state; the only route
     * there is `reset`, which is test machinery, so what is proven here is the starting value and the
     * transition out of it.
     */
    @Test
    fun `the state is Uninitialized before initialize and Ready after`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                val seen = mutableListOf<SdkState>()
                val subscribed = CompletableDeferred<Unit>()
                val ready = CompletableDeferred<Unit>()
                val collector =
                    backgroundScope.launch {
                        PayabliSession.state
                            .onSubscription { subscribed.complete(Unit) }
                            .collect {
                                seen += it
                                if (it == SdkState.Ready) ready.complete(Unit)
                            }
                    }
                // Registered before initialize runs, so the starting value is observed rather than raced for.
                assertNotNull(
                    "the collector never subscribed",
                    withTimeoutOrNull(COMPLETION_TIMEOUT) { subscribed.await() },
                )

                session(server, config())

                assertNotNull(
                    "the collector never saw the session become ready",
                    withTimeoutOrNull(COMPLETION_TIMEOUT) { ready.await() },
                )
                collector.cancel()
                assertEquals(listOf(SdkState.Uninitialized, SdkState.Ready), seen)
            }
        }

    @Test
    fun `a session is Ready once initialized`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                session(server, config())

                assertEquals(SdkState.Ready, PayabliSession.state.value)
            }
        }

    @Test
    fun `a straggler on a replaced session cannot finish its successor`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                // The listener the first session's transport was built with, captured rather than fired
                // through a request: it has to fire *late*, and a real request cannot be held past the
                // replacement.
                lateinit var straggler: AuthFailureListener
                PayabliSession
                    .initializeWith(config()) { onAuthFailure ->
                        straggler = onAuthFailure
                        TransportFactory.authenticatedAgainst(server.baseUrl, config(), Dispatchers.IO)
                    }.getOrThrow()

                straggler.onUnrecoverable(PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, "finished"))
                assertEquals(SdkState.ReinitializeRequired, PayabliSession.state.value)

                PayabliSession
                    .initializeWith(config()) { _ ->
                        TransportFactory.authenticatedAgainst(server.baseUrl, config(), Dispatchers.IO)
                    }.getOrThrow()
                assertEquals(SdkState.Ready, PayabliSession.state.value)

                // A request that decided the first session was finished can suspend before it says so, and
                // resume after the host has re-initialized. Keyed on the published value rather than on the
                // machine, this call kills a session that is live and the host has no signal that it did.
                straggler.onUnrecoverable(PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, "finished"))

                assertEquals(
                    "a listener from a replaced session must not finish the one that replaced it",
                    SdkState.Ready,
                    PayabliSession.state.value,
                )
            }
        }

    /**
     * `runBlocking` for the reason the mutex test above gives: this is about a real wait on a real lock.
     *
     * The wedge this describes is what teardown bounds itself against. A test that leaves the lock held makes
     * `reset` time out, and the state has to come back anyway, or one wedged test decides the outcome of
     * every class after it and none of them names the cause.
     */
    @Test
    fun `reset restores the state even when it cannot take the lock`() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                LoopbackServer().use { server ->
                    val insideBuilder = CompletableDeferred<Unit>()
                    val releaseBuilder = CompletableDeferred<Unit>()
                    try {
                        // A finished session, so the next initialize gets as far as its transport builder.
                        // While one is usable, install returns or refuses without ever calling the builder.
                        lateinit var straggler: AuthFailureListener
                        PayabliSession
                            .initializeWith(config()) { onAuthFailure ->
                                straggler = onAuthFailure
                                TransportFactory.authenticatedAgainst(server.baseUrl, config(), Dispatchers.IO)
                            }.getOrThrow()
                        straggler.onUnrecoverable(PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, "finished"))
                        assertEquals(SdkState.ReinitializeRequired, PayabliSession.state.value)

                        val initializing =
                            async(Dispatchers.IO) {
                                PayabliSession.initializeWith(config()) { _ ->
                                    insideBuilder.complete(Unit)
                                    releaseBuilder.await()
                                    TransportFactory.authenticatedAgainst(server.baseUrl, config(), Dispatchers.IO)
                                }
                            }
                        insideBuilder.await()

                        val resetting = async(Dispatchers.IO) { PayabliSession.reset() }
                        assertNull(
                            "reset should not have taken the lock initialize is holding",
                            withTimeoutOrNull(BLOCKED_PROBE) { resetting.await() },
                        )

                        // The half that runs before the lock. Without it a wedged lock leaves the state set,
                        // and the assertion in teardown fails in whichever class inherits it.
                        assertEquals(SdkState.Uninitialized, PayabliSession.state.value)

                        releaseBuilder.complete(Unit)
                        initializing.await().getOrThrow()
                        resetting.await()
                    } finally {
                        releaseBuilder.complete(Unit)
                    }
                }
            }
        }

    /**
     * `runBlocking` for the reason the mutex test above gives: this is about a real wait on a real lock.
     */
    @Test
    fun `reset clears the session an initialize installs while reset is waiting`() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                LoopbackServer().use { server ->
                    val insideBuilder = CompletableDeferred<Unit>()
                    val releaseBuilder = CompletableDeferred<Unit>()
                    try {
                        val initializing =
                            async(Dispatchers.IO) {
                                PayabliSession.initializeWith(config()) { _ ->
                                    insideBuilder.complete(Unit)
                                    releaseBuilder.await()
                                    TransportFactory.authenticatedAgainst(server.baseUrl, config(), Dispatchers.IO)
                                }
                            }
                        insideBuilder.await()

                        // Runs whatever it does before the lock, then waits: the window in which the session
                        // it is about to clear does not exist yet.
                        val resetting = async(Dispatchers.IO) { PayabliSession.reset() }
                        assertNull(
                            "reset should be waiting on the lock initialize holds",
                            withTimeoutOrNull(BLOCKED_PROBE) { resetting.await() },
                        )

                        releaseBuilder.complete(Unit)
                        initializing.await().getOrThrow()
                        resetting.await()

                        // Cleared against the session that ended up installed, not against the one that was
                        // there when reset started. Otherwise teardown leaves a live machine and a state the
                        // next class inherits.
                        assertEquals(SdkState.Uninitialized, PayabliSession.state.value)
                    } finally {
                        releaseBuilder.complete(Unit)
                    }
                }
            }
        }

    @Test
    fun `a straggler cannot publish over a state that has been reset`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                lateinit var straggler: AuthFailureListener
                PayabliSession
                    .initializeWith(config()) { onAuthFailure ->
                        straggler = onAuthFailure
                        TransportFactory.authenticatedAgainst(server.baseUrl, config(), Dispatchers.IO)
                    }.getOrThrow()

                PayabliSession.reset()

                // The same hazard as a replacement, in the arrangement every test in this file ends with. A
                // reset that only put the value back would leave the outgoing session able to write over it,
                // and the class that inherited the state would have nothing pointing back to the one that
                // leaked it.
                straggler.onUnrecoverable(PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, "finished"))

                assertEquals(
                    "a listener from a session that was reset away must not publish",
                    SdkState.Uninitialized,
                    PayabliSession.state.value,
                )
            }
        }

    @Test
    fun `a refreshed token refused again finishes the session`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                // Refuses everything, so the replay carrying the freshly minted token is rejected too.
                server.respondWith(401, "{}")
                val subject = session(server, config(tokenProvider = provider()))

                val failure =
                    runCatching {
                        completing("the request whose replay is refused") { subject.transport.execute(ping()) }
                    }.exceptionOrNull()

                assertEquals(PayabliErrorCode.TOKEN_EXPIRED, (failure as PayabliException).code)
                // A token minted seconds ago and refused again is an authorization fact, not a transient one.
                assertEquals(SdkState.ReinitializeRequired, PayabliSession.state.value)
            }
        }

    @Test
    fun `a finished transport refuses later requests without sending or refreshing`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondWith(401, "{}")
                val subject = session(server, config(tokenProvider = provider()))

                runCatching {
                    completing("the request that finishes the session") { subject.transport.execute(ping()) }
                }
                assertEquals(SdkState.ReinitializeRequired, PayabliSession.state.value)
                val sentWhileDying = server.recorded.size
                val refreshesWhileDying = providerCalls.get()

                val after =
                    runCatching {
                        completing("the request after the session was finished") {
                            subject.transport.execute(ping())
                        }
                    }.exceptionOrNull()

                // Telling the session is not enough on its own. Left unlatched, this request sends the
                // rejected token again, calls the host's broker again, and can even succeed, while the
                // session it belongs to says it must be re-initialized. Worse, the host then re-initializes
                // and a capability still holding this transport keeps a second refresh domain alive.
                assertEquals(PayabliErrorCode.TOKEN_EXPIRED, (after as PayabliException).code)
                assertEquals("a finished transport must not send again", sentWhileDying, server.recorded.size)
                assertEquals(
                    "a finished transport must not call the host's broker again",
                    refreshesWhileDying,
                    providerCalls.get(),
                )
            }
        }

    @Test
    fun `a recovered 401 leaves the session Ready`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                rejectingStale(server)
                val subject = session(server, config(tokenProvider = provider()))

                assertEquals(
                    200,
                    completing("the recovered request") { subject.transport.execute(ping()) }.statusCode,
                )

                // The discriminating case. Every 401 maps to TOKEN_EXPIRED, so anything inferring the state
                // from the error code would finish this session, which just recovered exactly as designed.
                assertEquals(SdkState.Ready, PayabliSession.state.value)
            }
        }

    @Test
    fun `a provider that fails once does not finish the session`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondWith(401, "{}")
                val failing =
                    PayabliTokenProvider {
                        providerCalls.incrementAndGet()
                        error("the host's broker is having a bad minute")
                    }
                val subject = session(server, config(tokenProvider = failing))

                runCatching { completing("the request whose refresh fails") { subject.transport.execute(ping()) } }

                // The distinction this rests on: a broker that failed once may well answer the
                // next call, and finishing the session for it would teach hosts to ignore the state that
                // means their session is genuinely finished.
                assertEquals(SdkState.Ready, PayabliSession.state.value)
                assertEquals(1, providerCalls.get())
            }
        }
}
