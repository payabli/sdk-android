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

    private fun config(provider: PayabliTokenProvider? = null) =
        PayabliConfig(
            accessToken = "initial-token",
            entryPoint = "entry",
            environment = PayabliEnvironment.SANDBOX,
            tokenProvider = provider,
        )

    private fun logger() = DefaultPayabliLogger(LogCategory.NETWORK, sink)

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
    fun `no token reaches the log through the factory path`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondInOrder(401 to "", 200 to "")

                val transport =
                    PayabliTransports.authenticatedAgainst(
                        server.baseUrl,
                        config { REFRESHED },
                        logger = logger(),
                    )
                completing("the call") { transport.execute(ping()) }

                val logged = sink.records.joinToString("\n") { it.message }
                assertTrue("something was logged", logged.isNotEmpty())
                assertEquals("no token in the log", false, logged.contains("initial-token"))
                assertEquals("no refreshed token either", false, logged.contains(REFRESHED))
            }
        }
}
