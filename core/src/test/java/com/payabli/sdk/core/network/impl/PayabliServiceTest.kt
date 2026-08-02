package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Exercises the real `HttpURLConnection` against a loopback server, so URL assembly, header handling,
 * body streaming and error-stream reading are covered by behaviour rather than by asserting on flags we
 * happened to set.
 */
class PayabliServiceTest {
    @Serializable
    private class Payload(
        val paymentTransId: String,
    )

    /**
     * A recording sink, not the default logger: `android.util.Log` throws "not mocked" on the JVM, and
     * injecting here keeps the redaction path under test rather than stubbed out.
     */
    private val sink = RecordingLogSink()

    private fun service(
        server: LoopbackServer,
        baseUrl: String = server.baseUrl,
        callTimeout: Duration = PayabliService.DEFAULT_CALL_TIMEOUT,
    ) = PayabliService.create(
        baseUrl = baseUrl,
        auth = testAuth(),
        logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
        callTimeout = callTimeout,
    )

    private fun loggedLines(): String = sink.records.joinToString("\n") { it.message }

    @Test
    fun `a GET round-trips status, body and headers`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, """{"ok":true}""", mapOf("X-Request-Id" to "abc123"))

                val response = service(server).execute(PayabliRequest(HttpMethod.GET, "/api/ping"))

                assertEquals(200, response.statusCode)
                assertTrue(response.isSuccessful)
                assertEquals("""{"ok":true}""", response.bodyAsText())
                assertEquals("abc123", response.header("x-request-id"))
                assertEquals("GET", server.onlyRequest.method)
                assertEquals("/api/ping", server.onlyRequest.path)
            }
        }

    @Test
    fun `a POST sends the body, the method and the caller's headers`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(201, "")

                val request =
                    PayabliRequest.json(
                        method = HttpMethod.POST,
                        path = "/api/v2/MoneyIn/getpaid",
                        body = Payload("txn-1"),
                        bodySerializer = Payload.serializer(),
                        headers = mapOf("X-Pyb-Client" to "android/0.1.0"),
                    )
                val response = service(server).execute(request)

                assertEquals(201, response.statusCode)
                val sent = server.onlyRequest
                assertEquals("POST", sent.method)
                assertEquals("""{"paymentTransId":"txn-1"}""", sent.body)
                assertEquals("application/json", sent.header("Content-Type"))
                assertEquals("android/0.1.0", sent.header("X-Pyb-Client"))
            }
        }

    @Test
    fun `query parameters are encoded and repeated keys are preserved in order`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "")

                service(server).execute(
                    PayabliRequest(
                        method = HttpMethod.GET,
                        path = "/api/list",
                        query = listOf("id" to "1", "id" to "2", "q" to "a b&c"),
                    ),
                )

                assertEquals("id=1&id=2&q=a+b%26c", server.onlyRequest.query)
            }
        }

    @Test
    fun `a non-2xx body is read from the error stream rather than throwing`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(402, """{"code":"D05","reason":"declined"}""")

                val response = service(server).execute(PayabliRequest(HttpMethod.POST, "/api/pay"))

                assertEquals(402, response.statusCode)
                assertTrue(response.bodyAsText().contains("D05"))
            }
        }

    @Test
    fun `a non-2xx with no body yields an empty body, not a failure`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(500, "")

                val response = service(server).execute(PayabliRequest(HttpMethod.GET, "/api/boom"))

                assertEquals(500, response.statusCode)
                assertEquals(0, response.body.size)
            }
        }

    @Test
    fun `a redirect is returned to the caller, never followed`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(
                    302,
                    "",
                    headers = mapOf("Location" to "https://example.invalid/elsewhere"),
                )

                val response = service(server).execute(PayabliRequest(HttpMethod.GET, "/api/moved"))

                // Following a 3xx would forward the Authorization header to the host the server names.
                assertEquals(302, response.statusCode)
                assertEquals("https://example.invalid/elsewhere", response.header("Location"))
                assertEquals(1, server.recorded.size)
            }
        }

    @Test
    fun `the base URL joins correctly whether or not the slashes line up`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "")
                val withSlash = service(server, baseUrl = server.baseUrl + "/")

                withSlash.execute(PayabliRequest(HttpMethod.GET, "api/one"))
                withSlash.execute(PayabliRequest(HttpMethod.GET, "/api/two"))
                service(server).execute(PayabliRequest(HttpMethod.GET, "/api/three"))

                assertEquals(listOf("/api/one", "/api/two", "/api/three"), server.recorded.map { it.path })
            }
        }

    @Test
    fun `the decoding overload returns a v2 envelope`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, """{"code":"A01","data":{"paymentTransId":"txn-9"}}""")

                val envelope =
                    service(server).execute(
                        PayabliRequest(HttpMethod.POST, "/api/v2/MoneyIn/initiate"),
                        Payload.serializer(),
                    )

                assertTrue(envelope.isApproved)
                assertEquals("txn-9", envelope.payload?.paymentTransId)
            }
        }

    @Test
    fun `a resolved path is redacted in logs when no route template is supplied`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "")

                service(server).execute(PayabliRequest(HttpMethod.GET, "/api/v2/MoneyIn/capture/9999999999"))

                val logged = loggedLines()
                assertFalse(logged.contains("9999999999"))
                assertFalse(logged.contains("/api/v2/MoneyIn/capture"))
                assertTrue(logged.contains("route=[REDACTED]"))
            }
        }

    @Test
    fun `a route template is emitted while the identifier in the path is not`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "")

                service(server).execute(
                    PayabliRequest(
                        method = HttpMethod.GET,
                        path = "/api/v2/MoneyIn/capture/9999999999",
                        route = "/api/v2/MoneyIn/capture/{id}",
                    ),
                )

                val logged = loggedLines()
                assertTrue(logged.contains("route=/api/v2/MoneyIn/capture/{id}"))
                assertFalse(logged.contains("9999999999"))
            }
        }

    @Test
    fun `status and duration are logged as allowlisted fields`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(402, """{"code":"D05"}""")

                service(server).execute(PayabliRequest(HttpMethod.POST, "/api/pay", route = "/api/pay"))

                val logged = loggedLines()
                assertTrue(logged.contains("statusCode=402"))
                assertTrue(logged.contains("method=POST"))
                assertTrue(logged.contains("durationMs="))
            }
        }

    @Test
    fun `execute applies the decoration chain it holds`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "")
                val transport =
                    PayabliService.createWithDecorations(
                        baseUrl = server.baseUrl,
                        decorations = listOf(PayabliRequestDecoration { it.withHeaders(mapOf("X-Probe" to "1")) }),
                        logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
                    )

                // The request carries no headers of its own, so anything that arrives came from the chain.
                transport.execute(PayabliRequest(HttpMethod.GET, "/api/ping"))

                assertEquals("1", server.onlyRequest.header("X-Probe"))
            }
        }

    @Test
    fun `a GET carries no request body and no content-type`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "")

                service(server).execute(PayabliRequest(HttpMethod.GET, "/api/ping"))

                // doOutput on a GET would silently rewrite the method to POST.
                assertEquals("GET", server.onlyRequest.method)
                assertEquals("", server.onlyRequest.body)
                assertNull(server.onlyRequest.header("Content-Type"))
            }
        }

    /**
     * The whole-call bound, which no socket-level timeout provides: the read timeout only ever bounds the
     * wait for the next byte.
     *
     * **Asserts the elapsed time, not only the error.** An earlier version checked the error alone and passed
     * at 810ms against a 200ms budget, because the call waited out the whole stall and failed afterwards. That
     * is what a test looks like when the mechanism it covers does not work.
     */
    @Test
    fun `a call that outlives its budget fails as a network error`() =
        runTest {
            val budgetMillis = 200L
            val stallMillis = 800L
            // The midpoint, which is what "ended nearer its budget than the stall" means. Derived rather
            // than written as a number, so it cannot drift from the two values it sits between.
            val cutoffMillis = (budgetMillis + stallMillis) / 2

            LoopbackServer().use { server ->
                server.respondWith(200, "").stallBeforeResponding(stallMillis)

                val startedAt = System.currentTimeMillis()
                val thrown =
                    runCatching {
                        service(server, callTimeout = budgetMillis.milliseconds)
                            .execute(PayabliRequest(HttpMethod.GET, "/api/ping"))
                    }.exceptionOrNull()
                val elapsed = System.currentTimeMillis() - startedAt

                assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
                assertEquals(PayabliErrorCode.NETWORK_ERROR, (thrown as PayabliException).code)
                // The request did reach the server, so the budget ended a call in flight rather than one
                // that never started.
                assertEquals("/api/ping", server.onlyRequest.path)
                // The load-bearing assertion, and it fails if the deadline stops tearing the socket down.
                assertTrue(
                    "the call waited out the stall instead of being cut off: ${elapsed}ms of a " +
                        "${stallMillis}ms stall, over a ${cutoffMillis}ms bound on a ${budgetMillis}ms budget",
                    elapsed < cutoffMillis,
                )
            }
        }

    /**
     * The case the whole-call bound exists for, and the one with no coverage until now.
     *
     * A peer that sends a byte at a time makes continuous progress, so every individual read completes far
     * inside the 10s socket read timeout and that timeout never fires. Only the whole-call deadline can end
     * this, and before the deadline actually tore the socket down it could not: the call ran to completion.
     */
    @Test
    fun `a dribbling peer is cut off by the call budget, which a read timeout cannot do`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "0123456789").dribbleBody(60)

                val startedAt = System.currentTimeMillis()
                val thrown =
                    runCatching {
                        service(server, callTimeout = 250.milliseconds)
                            .execute(PayabliRequest(HttpMethod.GET, "/api/ping"))
                    }.exceptionOrNull()
                val elapsed = System.currentTimeMillis() - startedAt

                assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
                assertEquals(PayabliErrorCode.NETWORK_ERROR, (thrown as PayabliException).code)
                assertTrue("the dribble was never cut off: ${elapsed}ms", elapsed < 1_500)
            }
        }
}
