package com.payabli.sdk.core

import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.config.PayabliTokenProvider
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.TransportFactory
import com.payabli.sdk.core.network.impl.LoopbackServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 10.seconds
private val COMPLETION_TIMEOUT = 5.seconds
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
    fun clearInstalledSession() {
        // Process-wide, like the log cutoff: a session left installed decides the outcome of the next class.
        runBlocking { PayabliSession.reset() }
    }

    private fun provider(token: String = FRESH) =
        PayabliTokenProvider {
            providerCalls.incrementAndGet()
            token
        }

    private fun config(
        accessToken: String = STALE,
        entryPoint: String = "entry",
        tokenProvider: PayabliTokenProvider? = null,
    ) = PayabliConfig(
        accessToken = accessToken,
        entryPoint = entryPoint,
        environment = PayabliEnvironment.SANDBOX,
        tokenProvider = tokenProvider,
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
                val first: PayabliTransport = TransportFactory.authenticatedAgainst(server.baseUrl, config)
                val second: PayabliTransport = TransportFactory.authenticatedAgainst(server.baseUrl, config)

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
                runCatching { completing("the request that condemns the session") { dead.transport.execute(ping()) } }
                assertEquals(SdkState.ReinitializeRequired, dead.state.value)

                // The documented recovery, and it has to work with a newly brokered token, so the new
                // configuration differs. Refusing here would leave the host with no way back.
                val revived = session(server, config(accessToken = "brokered-again"))

                assertNotSame("re-initializing after a condemned session must build a new one", dead, revived)
                assertEquals(SdkState.Ready, revived.state.value)
            }
        }

    // ---- state --------------------------------------------------------------------------------------

    @Test
    fun `a session is Ready once initialized`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                assertEquals(SdkState.Ready, session(server, config()).state.value)
            }
        }

    @Test
    fun `a refreshed token refused again condemns the session`() =
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
                assertEquals(SdkState.ReinitializeRequired, subject.state.value)
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
                // from the error code would condemn this session, which just recovered exactly as designed.
                assertEquals(SdkState.Ready, subject.state.value)
            }
        }

    @Test
    fun `a rejection with no token provider condemns the session`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondWith(401, "{}")
                val subject = session(server, config(tokenProvider = null))

                runCatching { completing("the unrefreshable request") { subject.transport.execute(ping()) } }

                // No provider means every future refresh fails the same way, so there is nothing to wait for.
                assertEquals(SdkState.ReinitializeRequired, subject.state.value)
                assertEquals("a session with no provider must not have called one", 0, providerCalls.get())
            }
        }

    @Test
    fun `a provider that fails once does not condemn the session`() =
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

                // The distinction the condemnation rests on: a broker that failed once may well answer the
                // next call, and condemning the session for it would teach hosts to ignore the state that
                // means their session is genuinely finished.
                assertEquals(SdkState.Ready, subject.state.value)
                assertEquals(1, providerCalls.get())
            }
        }
}
