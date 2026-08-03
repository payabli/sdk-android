package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.AuthRecoveryPolicy
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import com.payabli.sdk.core.network.Retry
import com.payabli.sdk.core.network.RetryPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds
private val COMPLETION_TIMEOUT = 2.seconds

private const val REFRESHED = "refreshed-token"
private const val AUTHORIZATION = "Authorization"
private const val UNAUTHORIZED = 401
private const val OK = 200

/** A status the default policy ignores, so only a widened one reaches the replay decision. */
private const val WIDENED = 419

/** Distinctive so a log assertion can tell a resolved path apart from the route template. */
private const val ID_SENTINEL = "PAY-9f3c1d-SENTINEL"
private const val ROUTE = "/api/pay/{id}"
private const val BODY_SENTINEL = """{"amount":"1.00","pan":"SENTINEL-NEVER-LOG"}"""

/**
 * The 2.3 pair: `BearerDecoration` inside the chain, and `AuthenticatedTransport` over it for the 401 dance.
 *
 * Exercised through the real [PayabliService] and a real socket rather than a fake base, because the
 * property that matters most is not expressible against a fake: the retry carries the refreshed token only
 * because re-entering the transport re-runs the chain. A fake base has no chain to re-run.
 */
class AuthenticatedTransportTest {
    @Serializable
    private class Payload(
        val paymentTransId: String,
    )

    private val sink = RecordingLogSink()

    private fun stack(
        server: LoopbackServer,
        auth: PayabliAuth,
        callTimeout: Duration = PayabliService.DEFAULT_CALL_TIMEOUT,
        recovery: AuthRecoveryPolicy = AuthRecoveryPolicy(),
    ): PayabliTransport =
        AuthenticatedTransport(
            base =
                PayabliService.create(
                    baseUrl = server.baseUrl,
                    auth = auth,
                    logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
                    callTimeout = callTimeout,
                ),
            auth = auth,
            recovery = recovery,
            logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
        )

    private fun ping() = PayabliRequest(HttpMethod.GET, "/api/ping", route = "/api/ping")

    /** A capability's own rule, of the shape [TransportFactory] exists to accept. */
    private fun widenedTo419() =
        object : AuthRecoveryPolicy() {
            override fun isCredentialRejection(response: PayabliResponse): Boolean =
                super.isCredentialRejection(response) || response.statusCode == WIDENED
        }

    /**
     * No body, deliberately. [CountingBase] ignores its request, so a body here would be read by nothing and
     * would look like coverage that does not exist. Body preservation across a replay is covered on a real
     * socket by `the retry re-sends the body unchanged`.
     */
    private fun guardedReplayRequest(method: HttpMethod): PayabliRequest =
        PayabliRequest(
            method = method,
            path = "/api/pay/$ID_SENTINEL",
            route = ROUTE,
        )

    private fun authWithCountingRefresh(calls: AtomicInteger): PayabliAuth =
        PayabliAuth(
            PayabliConfig(
                accessToken = "initial-token",
                entryPoint = "entry",
                environment = PayabliEnvironment.SANDBOX,
                tokenProvider = { REFRESHED.also { calls.incrementAndGet() } },
            ),
        )

    /**
     * Answers [WIDENED] once, then [OK], counting sends.
     *
     * A fake base rather than the loopback server, for the one property where that is the *better* level:
     * the replay decision belongs to [AuthenticatedTransport] and never touches the HTTP client. It is also
     * the only level where `PATCH` can be observed, because the JVM's `HttpURLConnection` rejects that verb
     * in `PayabliService.openConnection` before any I/O, as the comment there records. The socket-backed
     * paths stay covered by `the retry re-sends the body unchanged` and by `TransportFactoryTest`.
     */
    private class CountingBase(
        private val firstStatus: Int = WIDENED,
    ) : PayabliTransport {
        var sends = 0
            private set

        override suspend fun execute(request: PayabliRequest): PayabliResponse {
            sends++
            return PayabliResponse(if (sends == 1) firstStatus else OK)
        }

        override suspend fun <T> execute(
            request: PayabliRequest,
            payloadSerializer: KSerializer<T>,
        ): PayabliV2Envelope<T> = execute(request).asV2Envelope(payloadSerializer)
    }

    /**
     * Bounded so a wedge fails with what stalled rather than hanging.
     *
     * On a real dispatcher: `runTest`'s virtual clock expires the moment the scheduler runs dry, which is
     * while the socket call is still outstanding, so it would bound nothing.
     */
    private suspend fun <T : Any> completing(
        what: String,
        block: suspend () -> T,
    ): T =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(COMPLETION_TIMEOUT) { block() }
        } ?: throw AssertionError("$what never completed")

    private suspend fun failureFrom(block: suspend () -> Unit): PayabliException {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
        return thrown as PayabliException
    }

    // ---- acceptance -------------------------------------------------------------------------------

    @Test
    fun `the bearer is on every request`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondWith(OK, "")
                val auth = testAuth()

                // Through the base, not the wrapper: the chain is where this happens now, so asserting it
                // through the wrapper would pass even if the wrapper were the one injecting.
                PayabliService
                    .create(server.baseUrl, auth, DefaultSdkLogger(LogCategory.NETWORK, sink))
                    .execute(ping())

                assertEquals("Bearer $TEST_TOKEN", server.onlyRequest.header(AUTHORIZATION))
            }
        }

    @Test
    fun `one 401 triggers one refresh and one retry carrying the new token`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondInOrder(UNAUTHORIZED to "", OK to "second")
                val calls = AtomicInteger()
                val auth = testAuth(tokenProvider = { REFRESHED.also { calls.incrementAndGet() } })

                val response = completing("the authenticated call") { stack(server, auth).execute(ping()) }

                assertEquals(OK, response.statusCode)
                assertEquals("exactly one refresh", 1, calls.get())
                assertEquals("exactly two attempts", 2, server.recorded.size)
                assertEquals("Bearer $TEST_TOKEN", server.recorded[0].header(AUTHORIZATION))
                assertEquals("Bearer $REFRESHED", server.recorded[1].header(AUTHORIZATION))
            }
        }

    @Test
    fun `a second 401 is terminal and there is no third attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                // The script's last entry repeats, so a third attempt is served and counted rather than
                // hanging. The count is what fails if the retry-once bound were lost.
                server.respondInOrder(UNAUTHORIZED to "")
                val auth = testAuth(tokenProvider = { REFRESHED })

                val failure = failureFrom { stack(server, auth).execute(ping()) }

                assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)
                assertEquals("retry once, so two attempts and no more", 2, server.recorded.size)
            }
        }

    // ---- adversarial ------------------------------------------------------------------------------

    /**
     * End to end: a caller supplying its own bearer does not get it honoured.
     *
     * It does **not** prove the merge removes the caller's header rather than shadowing it, and the first
     * draft of this test claimed that it did. Sabotaging the removal leaves this green, because
     * `setRequestProperty` replaces case-insensitively and the decoration's value is merged last, so the
     * wire looks identical either way. The removal is pinned where it is observable, on the map:
     * `PayabliRequestDecorationTest.a differently-cased caller header is removed, not shadowed`.
     *
     * What this one does catch is the bearer not being applied at all, which the chain sabotage confirms.
     */
    @Test
    fun `a caller-supplied bearer is not what reaches the wire`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondWith(OK, "")
                val auth = testAuth()

                PayabliService
                    .create(server.baseUrl, auth, DefaultSdkLogger(LogCategory.NETWORK, sink))
                    .execute(
                        PayabliRequest(
                            HttpMethod.GET,
                            "/api/ping",
                            // Lower-cased on purpose: the merge is case-insensitive, so a same-cased key
                            // would not exercise the interesting half.
                            headers = mapOf("authorization" to "Bearer attacker-supplied"),
                        ),
                    )

                assertEquals("Bearer $TEST_TOKEN", server.onlyRequest.header(AUTHORIZATION))
                assertFalse(
                    "the caller's value must not survive anywhere in the request",
                    server.onlyRequest.headers.values
                        .flatten()
                        .any { it.contains("attacker-supplied") },
                )
            }
        }

    /**
     * The subject is de-duplication, so all five callers must hold the stale token before any of them is
     * answered. A barrier after the bearer decoration forces that.
     *
     * Two ways this test has been wrong. First it inferred which requests were retries from their position,
     * which five concurrent callers do not promise, and it failed on CI while passing locally. Fixing that by
     * asserting only order-independent facts then made it pass with no concurrency at all: one caller could
     * refresh and the other four sail through on the new token, still one provider call. The count of
     * stale-token attempts is the assertion that distinguishes the two, and it was the one missing.
     */
    @Test
    fun `five callers holding the stale token share a single refresh`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                val callers = 5
                val calls = AtomicInteger()
                val auth = testAuth(tokenProvider = { REFRESHED.also { calls.incrementAndGet() } })
                server.respondPerRequest { request ->
                    if (request.header(AUTHORIZATION) == "Bearer $TEST_TOKEN") UNAUTHORIZED to "" else OK to ""
                }

                // Parks after the bearer is stamped, so every caller is holding the stale token at once.
                val arrived = AtomicInteger()
                val allStamped = CompletableDeferred<Unit>()
                val barrier =
                    PayabliRequestDecoration { request ->
                        if (arrived.incrementAndGet() >= callers) allStamped.complete(Unit)
                        if (request.headers[AUTHORIZATION] == "Bearer $TEST_TOKEN") allStamped.await()
                        request
                    }
                val transport =
                    AuthenticatedTransport(
                        base =
                            PayabliService.createWithDecorations(
                                baseUrl = server.baseUrl,
                                decorations = listOf(BearerDecoration(auth), barrier),
                                logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
                            ),
                        auth = auth,
                    )

                val results = List(callers) { async { transport.execute(ping()) } }
                val statuses = completing("five concurrent authenticated calls") { results.map { it.await() } }

                val stale = server.recorded.count { it.header(AUTHORIZATION) == "Bearer $TEST_TOKEN" }
                assertEquals("all five held the stale token before any refresh", callers, stale)
                assertEquals("and they shared one provider call", 1, calls.get())
                assertTrue("every caller recovered", statuses.all { it.statusCode == OK })
            }
        }

    /**
     * A rotation landing between the wrapper reading the token and the chain reading it again.
     *
     * The two reads are separate, so they can disagree. If the wrapper then reports the token it *remembers*
     * as rejected, `invalidateAndRefresh` sees it is not current, takes the already-rotated branch, and hands
     * back the token that was actually just refused, without calling the provider. The replay then repeats a
     * known-bad credential and a recoverable failure is reported terminal.
     *
     * Forced rather than raced: a gate decoration parks the request before the bearer is read, the token is
     * rotated while it is parked, and the server refuses whatever the chain finally stamps.
     */
    @Test
    fun `a rotation between the two reads still refreshes the token that was actually sent`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                val calls = AtomicInteger()
                val minted = listOf("rotated-by-other", "minted-for-us")
                val auth = testAuth(tokenProvider = { minted[calls.getAndIncrement()] })
                val parked = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()

                // Only "minted-for-us" is accepted, so a replay of anything else is visible as a failure.
                server.respondPerRequest { request ->
                    if (request.header(AUTHORIZATION) == "Bearer minted-for-us") OK to "" else UNAUTHORIZED to ""
                }

                val gate =
                    PayabliRequestDecoration { request ->
                        if (parked.isCompleted) {
                            request
                        } else {
                            parked.complete(Unit)
                            release.await()
                            request
                        }
                    }
                val transport =
                    AuthenticatedTransport(
                        base =
                            PayabliService.createWithDecorations(
                                baseUrl = server.baseUrl,
                                decorations = listOf(gate, BearerDecoration(auth)),
                                logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
                            ),
                        auth = auth,
                    )

                val caller = async { transport.execute(ping()) }
                parked.await()
                // Someone else rotates while our request is parked before the bearer is read.
                auth.invalidateAndRefresh(TEST_TOKEN)
                release.complete(Unit)

                val response = completing("the call whose token rotated mid-flight") { caller.await() }

                assertEquals(OK, response.statusCode)
                assertEquals("the provider ran again for the token actually sent", 2, calls.get())
                assertEquals(
                    "the replay carried a freshly minted token, not the one just refused",
                    "Bearer minted-for-us",
                    server.recorded.last().header(AUTHORIZATION),
                )
            }
        }

    @Test
    fun `the decoding overload refreshes and retries too`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                val body = """{"code":"A01","data":{"paymentTransId":"txn-9"}}"""
                server.respondInOrder(UNAUTHORIZED to "", OK to body)
                val auth = testAuth(tokenProvider = { REFRESHED })

                val envelope =
                    completing("the decoded authenticated call") {
                        stack(server, auth).execute(ping(), Payload.serializer())
                    }

                // Would be a decode failure, or a raw 401, if the overload delegated to the base's.
                assertEquals("txn-9", envelope.payload?.paymentTransId)
                assertEquals(2, server.recorded.size)
                assertEquals("Bearer $REFRESHED", server.recorded[1].header(AUTHORIZATION))
            }
        }

    @Test
    fun `a decoded route ending in two 401s reports token expired, not a decode failure`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                // An empty body would not decode, so a mapper running after the decode would report the
                // wrong code and bury the real cause.
                server.respondInOrder(UNAUTHORIZED to "")
                val auth = testAuth(tokenProvider = { REFRESHED })

                val failure = failureFrom { stack(server, auth).execute(ping(), Payload.serializer()) }

                assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)
            }
        }

    /**
     * The interleaving an entry check alone cannot stop.
     *
     * A request that passed the terminal check before anything was condemned keeps going. When its own
     * rejection arrives it asks for a refresh, and if that request could still take a claim it would call
     * the host's broker and might succeed, after the host had been told to re-initialize and had built a
     * second session. `PayabliAuth` refuses the claim instead, so the broker is never reached.
     */
    @Test
    fun `a request already under way cannot reach the broker after another finishes the auth`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                val calls = AtomicInteger()
                val auth = testAuth(tokenProvider = { "refreshed-${calls.incrementAndGet()}" })
                val subject = stack(server, auth)

                // Condemn first, exactly as a sibling request would have: the token is unchanged and no
                // refresh is running, so this is the settled case the choke-point acts on.
                assertTrue(
                    auth.finishIfSettledOn(TEST_TOKEN, AuthRecoveryPolicy().exhausted()),
                )
                val callsWhenFinished = calls.get()

                server.respondWith(UNAUTHORIZED, "")
                val failure =
                    runCatching {
                        completing("the request issued against a finished auth") { subject.execute(ping()) }
                    }.exceptionOrNull()

                assertEquals(PayabliErrorCode.TOKEN_EXPIRED, (failure as PayabliException).code)
                assertEquals(
                    "a finished auth must not call the host's broker again",
                    callsWhenFinished,
                    calls.get(),
                )
            }
        }

    /**
     * A rejection that arrives after somebody else replaced the token.
     *
     * A second rejection normally means the credential is finished. That conclusion is only sound about the
     * token this attempt actually sent: if another caller replaced it while the reply was in flight, the
     * rejection describes a credential already gone, and acting on it would end a session whose current
     * token works. Ending it is permanent, so a wrong answer here is not recoverable.
     */
    @Test
    fun `a rejection of a token already replaced fails the caller without latching the transport`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                val minted = AtomicInteger()
                val auth = testAuth(tokenProvider = { "rotated-${minted.incrementAndGet()}" })
                val subject = stack(server, auth)

                // Counted here rather than read from the server, which indexes before appending, so the
                // first request would otherwise report zero and the branches would be off by one.
                val attempt = AtomicInteger()
                server.respondPerRequest { recorded ->
                    when (attempt.incrementAndGet()) {
                        1 -> UNAUTHORIZED to ""
                        // The replay. Rotate the holder before answering, so by the time this rejection is
                        // read the token it carried is no longer the one the holder would send.
                        2 -> {
                            val inFlight = recorded.header(AUTHORIZATION).orEmpty().removePrefix("Bearer ")
                            runBlocking { auth.invalidateAndRefresh(inFlight) }
                            UNAUTHORIZED to ""
                        }
                        else -> OK to ""
                    }
                }

                val failure =
                    runCatching {
                        completing("the request whose token was replaced under it") { subject.execute(ping()) }
                    }.exceptionOrNull()

                // The caller fails, because its own request did not succeed.
                assertEquals(PayabliErrorCode.TOKEN_EXPIRED, (failure as PayabliException).code)

                // The transport does not, because the token it holds now was never refused. Without the
                // staleness check this second request throws from the latch instead of reaching the server.
                assertEquals(
                    OK,
                    completing("a later request on a transport that was not condemned") {
                        subject.execute(ping())
                    }.statusCode,
                )
            }
        }

    @Test
    fun `the retry re-sends the body unchanged`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondInOrder(UNAUTHORIZED to "", OK to "")
                val auth = testAuth(tokenProvider = { REFRESHED })
                val payload = """{"amount":"1.00"}"""

                completing("the authenticated POST") {
                    stack(server, auth).execute(
                        PayabliRequest(
                            HttpMethod.POST,
                            "/api/pay",
                            body = payload.toByteArray(Charsets.UTF_8),
                        ),
                    )
                }

                assertEquals(2, server.recorded.size)
                assertEquals("the first attempt sent the body", payload, server.recorded[0].body)
                assertEquals("the retry sent the same body", payload, server.recorded[1].body)
            }
        }

    @Test
    fun `a 401 with no provider is terminal and is not retried`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondInOrder(UNAUTHORIZED to "")
                val auth = testAuth(tokenProvider = null)

                val failure = failureFrom { stack(server, auth).execute(ping()) }

                assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)
                assertEquals("nothing to refresh with, so no second attempt", 1, server.recorded.size)
            }
        }

    @Test
    fun `a failing provider is terminal, not retried, and does not leak its own message`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondInOrder(UNAUTHORIZED to "")
                val sentinel = "SENTINEL-PROVIDER-DETAIL"
                val auth = testAuth(tokenProvider = { throw IOException(sentinel) })

                val failure = failureFrom { stack(server, auth).execute(ping()) }

                assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)
                assertEquals("the refresh failed, so no second attempt", 1, server.recorded.size)
                assertFalse(
                    "the provider's own message must not reach the caller",
                    (failure.message ?: "").contains(sentinel),
                )
            }
        }

    @Test
    fun `a non-401 failure is returned untouched and triggers no refresh`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                val calls = AtomicInteger()
                server.respondInOrder(500 to "boom")
                val auth = testAuth(tokenProvider = { REFRESHED.also { calls.incrementAndGet() } })

                val response = completing("the authenticated call") { stack(server, auth).execute(ping()) }

                assertEquals(500, response.statusCode)
                assertEquals("only a 401 means the credential was refused", 0, calls.get())
                assertEquals(1, server.recorded.size)
            }
        }

    @Test
    fun `a provider that issues its own request through the SDK does not deadlock`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                // The bearer decoration reads the token per request, so a provider that reaches the
                // transport re-enters the holder while its own refresh is in flight.
                server.respondInOrder(UNAUTHORIZED to "", OK to "", OK to "")
                lateinit var transport: PayabliTransport
                var nested: Int? = null
                val auth =
                    testAuth(
                        tokenProvider = {
                            nested = transport.execute(ping()).statusCode
                            REFRESHED
                        },
                    )
                transport = stack(server, auth)

                val response = completing("a refresh whose provider calls back in") { transport.execute(ping()) }

                assertEquals(OK, response.statusCode)
                assertNotNull("the nested request should have completed", nested)
            }
        }

    /**
     * The two retry layers must not compound. `RetryPolicy` excludes `TOKEN_EXPIRED` from
     * `RETRYABLE_CODES` on purpose, deferring it to "a different mechanism", so a terminal 401 escaping
     * this class has to stop at `Retry` rather than be replayed three more times.
     *
     * On a real dispatcher because the socket work is real: `runTest`'s virtual clock advances the moment
     * the scheduler runs dry, so a bound taken against it would elapse while the call is still outstanding.
     * Not because of any deadline inside `Retry`, which has none per attempt.
     */
    @Test
    fun `a terminal 401 stops at the retry layer instead of being replayed`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondInOrder(UNAUTHORIZED to "")
                val auth = testAuth(tokenProvider = { REFRESHED })
                val transport = stack(server, auth)

                val failure =
                    failureFrom {
                        withContext(Dispatchers.IO) {
                            Retry.run(
                                policy =
                                    RetryPolicy(
                                        maxAttempts = 3,
                                        baseDelayMillis = 0,
                                        maxJitterMillis = 0,
                                        jitter = RetryPolicy.Jitter.None,
                                    ),
                                logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
                            ) { transport.execute(ping()) }
                        }
                    }

                assertEquals(PayabliErrorCode.TOKEN_EXPIRED, failure.code)
                // Two: the original and the one retry after refresh. Retry contributed none of its three.
                assertEquals("the retry layer must not replay a terminal 401", 2, server.recorded.size)
            }
        }

    /**
     * The policy is a seam only if the mechanism actually asks it. A policy that reports no rejection must
     * make a 401 pass straight through untouched: no refresh, no replay, no failure.
     *
     * This is the test that fails if `AuthenticatedTransport` goes back to testing `statusCode == 401`
     * itself, which is the difference between a seam and decoration that resembles one.
     */
    @Test
    fun `the mechanism obeys the policy rather than re-deriving the status`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondInOrder(UNAUTHORIZED to "body")
                val calls = AtomicInteger()
                val auth = testAuth(tokenProvider = { REFRESHED.also { calls.incrementAndGet() } })
                val neverRecovers =
                    object : AuthRecoveryPolicy() {
                        override fun isCredentialRejection(response: PayabliResponse): Boolean = false
                    }
                val transport =
                    AuthenticatedTransport(
                        base =
                            PayabliService.create(
                                baseUrl = server.baseUrl,
                                auth = auth,
                                logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
                            ),
                        auth = auth,
                        recovery = neverRecovers,
                    )

                val response = completing("the unrecovered call") { transport.execute(ping()) }

                assertEquals("the 401 is passed through, not raised", UNAUTHORIZED, response.statusCode)
                assertEquals("no refresh, because the policy saw no rejection", 0, calls.get())
                assertEquals("no replay either", 1, server.recorded.size)
            }
        }

    @Test
    fun `no token reaches the log, before or after a refresh`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondInOrder(UNAUTHORIZED to "", OK to "")
                val auth = testAuth(tokenProvider = { REFRESHED })

                completing("the authenticated call") { stack(server, auth).execute(ping()) }

                val logged = sink.records.joinToString("\n") { it.message }
                assertFalse("the initial token was logged", logged.contains(TEST_TOKEN))
                assertFalse("the refreshed token was logged", logged.contains(REFRESHED))
            }
        }

    /**
     * A refresh slower than one call's whole-call budget still runs exactly once.
     *
     * The budget belongs to the transport and bounds one exchange, so the refresh between the two calls is
     * not inside it and cannot be cut short. Were the budget instead wrapped around the whole operation, the
     * refresh would be cancelled and each further attempt would call the provider again with the token that
     * was already rejected.
     *
     * Millisecond scale, in the ratio the shipped defaults have: 10s against 30s would be a 30-second test.
     */
    @Test
    fun `a refresh slower than one call budget runs once and still recovers`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                val calls = AtomicInteger()
                val auth =
                    PayabliAuth(
                        PayabliConfig(
                            accessToken = TEST_TOKEN,
                            entryPoint = "entry",
                            environment = PayabliEnvironment.SANDBOX,
                            tokenProvider = {
                                Thread.sleep(600)
                                REFRESHED.also { calls.incrementAndGet() }
                            },
                        ),
                        DefaultSdkLogger(LogCategory.AUTH, sink),
                        providerTimeoutMillis = 5_000,
                    )
                server.respondPerRequest { request ->
                    if (request.header(AUTHORIZATION) == "Bearer $TEST_TOKEN") UNAUTHORIZED to "" else OK to ""
                }

                val outcome =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            Retry.run(
                                policy = RetryPolicy(maxAttempts = 3, baseDelayMillis = 0, maxJitterMillis = 0),
                                logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
                            ) { stack(server, auth, callTimeout = 300.milliseconds).execute(ping()) }
                        }
                    }

                // Asserted before the outcome so a budget that does reach the refresh is reported as the
                // repeated provider call it is, rather than as whatever the exhausted retry threw.
                assertEquals("the provider ran more than once", 1, calls.get())
                assertEquals(OK, outcome.getOrThrow().statusCode)
            }
        }

    // ---- what a widened policy may and may not replay --------------------------------------------
    //
    // The policy is `open` and the factory accepts a replacement, so widening is supported. Replaying is
    // not the policy's call: RFC 9110 Section 9.2.2 makes PUT, DELETE and the safe methods idempotent, and
    // only a 401 carries the separate argument that the request was refused before processing. A POST or
    // PATCH on any other status has neither, so replaying one could charge twice.
    //
    // A 401 on a POST still replaying is `the retry re-sends the body unchanged` above.

    /** Asserts one method's replay outcome under a policy widened to [WIDENED]. */
    @Test
    fun `a declined replay says why, without the token, the body or the resolved path`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondInOrder(WIDENED to "", OK to "")
                val auth = testAuth(tokenProvider = { REFRESHED })

                completing("the declined replay") {
                    stack(server, auth, recovery = widenedTo419()).execute(
                        PayabliRequest(
                            HttpMethod.POST,
                            "/api/pay/$ID_SENTINEL",
                            route = ROUTE,
                            body = BODY_SENTINEL.toByteArray(Charsets.UTF_8),
                        ),
                    )
                }

                val logged = sink.records.joinToString("\n") { it.message }
                // Presence first: a gutted log would satisfy every absence assertion below on its own.
                assertTrue("no record explains the declined replay", logged.contains("replay declined"))
                assertTrue("the method is what makes it explicable", logged.contains("method=POST"))
                assertTrue("so is the status", logged.contains("statusCode=$WIDENED"))
                assertTrue("the route template is loggable", logged.contains("route=$ROUTE"))

                assertFalse("the resolved path was logged", logged.contains(ID_SENTINEL))
                assertFalse("the body was logged", logged.contains("SENTINEL-NEVER-LOG"))
                assertFalse("the initial token was logged", logged.contains(TEST_TOKEN))
                assertFalse("the refreshed token was logged", logged.contains(REFRESHED))
            }
        }

    @Test
    fun `a 401 on POST still refreshes and replays`() =
        runTest(timeout = TEST_TIMEOUT) {
            val base = CountingBase(firstStatus = UNAUTHORIZED)
            val calls = AtomicInteger()
            val subject =
                AuthenticatedTransport(
                    base = base,
                    auth = authWithCountingRefresh(calls),
                    recovery = AuthRecoveryPolicy(),
                    logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
                )

            val response = subject.execute(guardedReplayRequest(HttpMethod.POST))

            assertEquals(OK, response.statusCode)
            assertEquals("one refresh", 1, calls.get())
            assertEquals("401 is replayable even for POST", 2, base.sends)
        }

    @Test
    fun `a widened rejection on GET refreshes and replays`() =
        runTest(timeout = TEST_TIMEOUT) {
            val base = CountingBase()
            val calls = AtomicInteger()
            val subject =
                AuthenticatedTransport(
                    base = base,
                    auth = authWithCountingRefresh(calls),
                    recovery = widenedTo419(),
                    logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
                )

            val response = subject.execute(guardedReplayRequest(HttpMethod.GET))

            assertEquals(OK, response.statusCode)
            assertEquals("one refresh", 1, calls.get())
            assertEquals("GET is safe and therefore replayable", 2, base.sends)
        }

    @Test
    fun `a widened rejection on PUT refreshes and replays`() =
        runTest(timeout = TEST_TIMEOUT) {
            val base = CountingBase()
            val calls = AtomicInteger()
            val subject =
                AuthenticatedTransport(
                    base = base,
                    auth = authWithCountingRefresh(calls),
                    recovery = widenedTo419(),
                    logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
                )

            val response = subject.execute(guardedReplayRequest(HttpMethod.PUT))

            assertEquals(OK, response.statusCode)
            assertEquals("one refresh", 1, calls.get())
            assertEquals("PUT is idempotent and therefore replayable", 2, base.sends)
        }

    @Test
    fun `a widened rejection on DELETE refreshes and replays`() =
        runTest(timeout = TEST_TIMEOUT) {
            val base = CountingBase()
            val calls = AtomicInteger()
            val subject =
                AuthenticatedTransport(
                    base = base,
                    auth = authWithCountingRefresh(calls),
                    recovery = widenedTo419(),
                    logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
                )

            val response = subject.execute(guardedReplayRequest(HttpMethod.DELETE))

            assertEquals(OK, response.statusCode)
            assertEquals("one refresh", 1, calls.get())
            assertEquals("DELETE is idempotent and therefore replayable", 2, base.sends)
        }

    @Test
    fun `a widened rejection on POST refreshes but returns the original response without replay`() =
        runTest(timeout = TEST_TIMEOUT) {
            val base = CountingBase()
            val calls = AtomicInteger()
            val subject =
                AuthenticatedTransport(
                    base = base,
                    auth = authWithCountingRefresh(calls),
                    recovery = widenedTo419(),
                    logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
                )

            val response = subject.execute(guardedReplayRequest(HttpMethod.POST))

            assertEquals(WIDENED, response.statusCode)
            assertEquals("the token was still refreshed for the next request", 1, calls.get())
            assertEquals("POST is not replayable for widened non-401 statuses", 1, base.sends)
        }

    @Test
    fun `a widened rejection on PATCH refreshes but returns the original response without replay`() =
        runTest(timeout = TEST_TIMEOUT) {
            val base = CountingBase()
            val calls = AtomicInteger()
            val subject =
                AuthenticatedTransport(
                    base = base,
                    auth = authWithCountingRefresh(calls),
                    recovery = widenedTo419(),
                    logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
                )

            val response = subject.execute(guardedReplayRequest(HttpMethod.PATCH))

            assertEquals(WIDENED, response.statusCode)
            assertEquals("the token was still refreshed for the next request", 1, calls.get())
            assertEquals("PATCH is not replayable for widened non-401 statuses", 1, base.sends)
        }
}
