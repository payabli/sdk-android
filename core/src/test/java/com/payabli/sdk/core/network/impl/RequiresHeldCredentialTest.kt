package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.config.PayabliTokenProvider
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import com.payabli.sdk.testutils.network.LoopbackServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/** Well under the provider's own ceiling: a request that waited on a refresh would not answer inside it. */
private val NO_WAIT = 1.seconds

private const val MINTED = "minted-token"
private const val AUTHORIZATION = "Authorization"
private const val OK = 200

/** A request that sends on the held credential only: it never calls the host's provider, whatever is held. */
class RequiresHeldCredentialTest {
    private val recorded = mutableListOf<String>()
    private val sink = RecordingLogSink()

    @Before
    fun install() = TelemetryRecorders.install { event, _ -> recorded += event }

    @After
    fun clear() = TelemetryRecorders.clear()

    private fun auth(provider: PayabliTokenProvider): PayabliAuth =
        PayabliAuth(
            PayabliConfig(entryPoint = "entry", environment = PayabliEnvironment.SANDBOX, tokenProvider = provider),
            RecordingSdkLogger(),
        )

    private fun stack(
        server: LoopbackServer,
        auth: PayabliAuth,
    ): AuthenticatedTransport =
        AuthenticatedTransport(
            base =
                PayabliService.create(
                    client = ClientFacts.NONE,
                    baseUrl = server.baseUrl,
                    auth = auth,
                    dispatcher = Dispatchers.IO,
                    logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
                ),
            auth = auth,
            logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
        )

    private fun heldOnly() =
        PayabliRequest(HttpMethod.POST, "/api/report", route = "/api/report", requiresHeldCredential = true)

    private fun ordinary() = PayabliRequest(HttpMethod.GET, "/api/ping", route = "/api/ping")

    private fun counting(calls: AtomicInteger) = PayabliTokenProvider { MINTED.also { calls.incrementAndGet() } }

    private suspend fun failureFrom(block: suspend () -> Unit): PayabliException {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
        return thrown as PayabliException
    }

    @Test
    fun `with nothing minted it calls no provider, sends nothing and answers MISSING_TOKEN`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondWith(OK, "")
                val calls = AtomicInteger()

                val failure = failureFrom { stack(server, auth(counting(calls))).execute(heldOnly()) }

                assertEquals(PayabliErrorType.MISSING_TOKEN, failure.type)
                assertEquals("no provider call", 0, calls.get())
                assertTrue("nothing reaches the service", server.recorded.isEmpty())
                assertTrue("no token failure is reported", TelemetryEvents.AUTH_TOKEN_FAILED !in recorded)
            }
        }

    @Test
    fun `once the host's own work has minted, it sends on that token without a further provider call`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondWith(OK, "")
                val calls = AtomicInteger()
                val transport = stack(server, auth(counting(calls)))
                transport.execute(ordinary())

                val response = transport.execute(heldOnly())

                assertEquals(OK, response.statusCode)
                assertEquals("only the host's own call minted", 1, calls.get())
                assertEquals("Bearer $MINTED", server.recorded.last().header(AUTHORIZATION))
            }
        }

    @Test
    fun `while a mint is in flight it answers at once rather than joining it`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondWith(OK, "")
                val calls = AtomicInteger()
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<String>()
                val subject =
                    auth {
                        calls.incrementAndGet()
                        started.complete(Unit)
                        release.await()
                    }
                val minting = async(Dispatchers.Default) { subject.accessToken() }
                started.await()

                // Real time: under virtual time, waiting on the lock the minting thread holds
                // can advance the clock past NO_WAIT.
                val failure =
                    failureFrom {
                        withContext(Dispatchers.Default) {
                            withTimeout(NO_WAIT) { stack(server, subject).execute(heldOnly()) }
                        }
                    }

                assertEquals(PayabliErrorType.MISSING_TOKEN, failure.type)
                release.complete(MINTED)
                assertEquals(MINTED, minting.await())
                assertEquals("the mint in flight is the only provider call", 1, calls.get())
                assertTrue("nothing reaches the service", server.recorded.isEmpty())
            }
        }

    @Test
    fun `a finished session refuses it with no provider call and no token failure reported`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondWith(OK, "")
                val calls = AtomicInteger()
                val subject = auth(counting(calls))
                subject.finish(PayabliGenericException(PayabliErrorType.SESSION_BURNED, "finished"))

                failureFrom { stack(server, subject).execute(heldOnly()) }

                assertEquals("no provider call", 0, calls.get())
                assertTrue("nothing reaches the service", server.recorded.isEmpty())
                assertTrue("no token failure is reported", TelemetryEvents.AUTH_TOKEN_FAILED !in recorded)
            }
        }

    @Test
    fun `an ordinary request with nothing minted still mints through the provider`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondWith(OK, "")
                val calls = AtomicInteger()

                stack(server, auth(counting(calls))).execute(ordinary())

                assertEquals(1, calls.get())
                assertEquals("Bearer $MINTED", server.onlyRequest.header(AUTHORIZATION))
            }
        }

    @Test
    fun `a provider that fails during the host's mint is not called again by a held-only request`() =
        runTest(timeout = TEST_TIMEOUT) {
            LoopbackServer().use { server ->
                server.respondWith(OK, "")
                val calls = AtomicInteger()
                val transport =
                    stack(
                        server,
                        auth {
                            calls.incrementAndGet()
                            throw IOException("provider down")
                        },
                    )
                failureFrom { transport.execute(ordinary()) }
                recorded.clear()

                val failure = failureFrom { transport.execute(heldOnly()) }

                assertEquals(PayabliErrorType.MISSING_TOKEN, failure.type)
                assertEquals("only the host's own call reached the provider", 1, calls.get())
                assertTrue("no token failure is reported for the skip", TelemetryEvents.AUTH_TOKEN_FAILED !in recorded)
            }
        }
}
