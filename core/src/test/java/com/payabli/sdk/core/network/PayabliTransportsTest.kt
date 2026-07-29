package com.payabli.sdk.core.network

import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.config.PayabliTokenProvider
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultPayabliLogger
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.impl.LoopbackServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds
private val COMPLETION_TIMEOUT = 2.seconds
private const val AUTHORIZATION = "Authorization"
private const val REFRESHED = "refreshed-token"

/**
 * The single entry point a separately-shipped capability uses to get a transport.
 *
 * These assert it hands back something already correct: bearer stamped, 401 recovered. A factory that
 * returned a transport missing either would be worse than no factory, because the caller cannot tell.
 */
class PayabliTransportsTest {
    private val sink = RecordingLogSink()
    private val authSink = RecordingLogSink()

    private fun config(provider: PayabliTokenProvider? = null) =
        PayabliConfig(
            accessToken = "initial-token",
            entryPoint = "entry",
            environment = PayabliEnvironment.SANDBOX,
            tokenProvider = provider,
        )

    private fun logger() = DefaultPayabliLogger(LogCategory.NETWORK, sink)

    private fun authLogger() = DefaultPayabliLogger(LogCategory.AUTH, authSink)

    private suspend fun <T : Any> completing(
        what: String,
        block: suspend () -> T,
    ): T =
        withContext(Dispatchers.IO) { withTimeoutOrNull(COMPLETION_TIMEOUT) { block() } }
            ?: throw AssertionError("$what never completed")

    private fun ping() = PayabliRequest(HttpMethod.GET, "/api/ping", route = "/api/ping")

    @Test
    fun `the transport it returns already stamps the bearer`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondWith(200, "")

                val transport =
                    PayabliTransports.authenticatedAgainst(server.baseUrl, config(), logger = logger())
                completing("the call") { transport.execute(ping()) }

                assertEquals("Bearer initial-token", server.onlyRequest.header(AUTHORIZATION))
            }
        }

    @Test
    fun `the transport it returns recovers a 401 and replays with the new token`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondInOrder(401 to "", 200 to "")
                val calls = AtomicInteger()

                val transport =
                    PayabliTransports.authenticatedAgainst(
                        server.baseUrl,
                        config { REFRESHED.also { calls.incrementAndGet() } },
                        logger = logger(),
                    )
                val response = completing("the recovered call") { transport.execute(ping()) }

                assertEquals(200, response.statusCode)
                assertEquals("one refresh", 1, calls.get())
                assertEquals(2, server.recorded.size)
                assertEquals("Bearer initial-token", server.recorded[0].header(AUTHORIZATION))
                assertEquals("Bearer $REFRESHED", server.recorded[1].header(AUTHORIZATION))
            }
        }

    @Test
    fun `a second 401 is terminal through the factory too`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondInOrder(401 to "")

                val transport =
                    PayabliTransports.authenticatedAgainst(
                        server.baseUrl,
                        config { REFRESHED },
                        logger = logger(),
                    )
                val thrown = runCatching { transport.execute(ping()) }.exceptionOrNull()

                assertTrue("got $thrown", thrown is PayabliException)
                assertEquals(PayabliErrorCode.TOKEN_EXPIRED, (thrown as PayabliException).code)
                assertEquals(2, server.recorded.size)
            }
        }

    @Test
    fun `a caller-supplied recovery policy is honoured, so a capability can widen what it recovers`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                // 419 means nothing to the default policy; this one treats it as a rejection.
                server.respondInOrder(419 to "", 200 to "")
                val calls = AtomicInteger()
                val widened =
                    object : AuthRecoveryPolicy() {
                        override fun isCredentialRejection(response: PayabliResponse): Boolean =
                            super.isCredentialRejection(response) || response.statusCode == 419
                    }

                val transport =
                    PayabliTransports.authenticatedAgainst(
                        server.baseUrl,
                        config { REFRESHED.also { calls.incrementAndGet() } },
                        recovery = widened,
                        logger = logger(),
                    )
                val response = completing("the widened recovery") { transport.execute(ping()) }

                assertEquals(200, response.statusCode)
                assertEquals("the capability's own rule drove a refresh", 1, calls.get())
                assertEquals("Bearer $REFRESHED", server.recorded[1].header(AUTHORIZATION))
            }
        }

    @Test
    fun `each call builds its own token holder, which is why one instance must be shared`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondInOrder(401 to "", 200 to "", 401 to "", 200 to "")
                val calls = AtomicInteger()
                val cfg = config { REFRESHED.also { calls.incrementAndGet() } }

                val first = PayabliTransports.authenticatedAgainst(server.baseUrl, cfg, logger = logger())
                val second = PayabliTransports.authenticatedAgainst(server.baseUrl, cfg, logger = logger())
                assertNotSame(first, second)

                completing("first") { first.execute(ping()) }
                completing("second") { second.execute(ping()) }

                // Two holders, so the same rejection is refreshed twice. Pinned so the KDoc's warning is a
                // measured fact rather than a caution, and so the session inherits a tested reason to exist.
                assertEquals("two independent refresh domains", 2, calls.get())
            }
        }

    @Test
    fun `no token reaches either log, and the refresh is logged under auth`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondInOrder(401 to "", 200 to "")

                val transport =
                    PayabliTransports.authenticatedAgainst(
                        server.baseUrl,
                        config { REFRESHED },
                        logger = logger(),
                        authLogger = authLogger(),
                    )
                completing("the call") { transport.execute(ping()) }

                val network = sink.records.joinToString("\n") { it.message }
                val auth = authSink.records.joinToString("\n") { it.message }

                // Positive first. Asserting only absence lets the test pass when nothing was logged at all,
                // which is exactly what happened when the holder stopped sharing the network logger.
                assertTrue("the refresh is logged under auth, not network: $auth", auth.contains("token_refreshed"))
                assertTrue("the request is logged under network", network.contains("route=/api/ping"))
                assertEquals("the refresh does not land under network", false, network.contains("token_refreshed"))

                for ((name, log) in listOf("network" to network, "auth" to auth)) {
                    assertEquals("initial token in the $name log", false, log.contains("initial-token"))
                    assertEquals("refreshed token in the $name log", false, log.contains(REFRESHED))
                }
            }
        }

    /**
     * The documented layering, with a provider slower than an attempt would previously tolerate.
     *
     * The outer attempt budget must contain the refresh. When it did not, the timeout surfaced as a retryable
     * NETWORK_ERROR, the next attempt started with the same rejected token, and the provider was invoked once
     * per attempt. This asserts one provider call and one replay for the whole operation.
     *
     * On a real dispatcher: Retry's per-attempt budget is a wall-clock deadline, and the socket work is real.
     */
    @Test
    fun `Retry around the authenticated transport does not preempt a slow refresh`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                val calls = AtomicInteger()
                // Slower than a token mint should be, and well inside the attempt budget.
                val slowProvider =
                    config {
                        Thread.sleep(600)
                        REFRESHED.also { calls.incrementAndGet() }
                    }
                server.respondPerRequest { request ->
                    if (request.header(AUTHORIZATION) == "Bearer initial-token") 401 to "" else 200 to ""
                }
                val transport =
                    PayabliTransports.authenticatedAgainst(
                        server.baseUrl,
                        slowProvider,
                        logger = logger(),
                        authLogger = authLogger(),
                    )

                val response =
                    completing("the retried authenticated call") {
                        Retry.run(
                            policy = RetryPolicy(maxAttempts = 3, baseDelayMillis = 0, maxJitterMillis = 0),
                            logger = logger(),
                        ) { transport.execute(ping()) }
                    }

                assertEquals(200, response.statusCode)
                assertEquals("one refresh for the whole operation, not one per attempt", 1, calls.get())
                assertEquals("one rejection and one replay", 2, server.recorded.size)
            }
        }
}
