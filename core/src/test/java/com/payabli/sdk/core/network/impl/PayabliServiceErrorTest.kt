package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultPayabliLogger
import com.payabli.sdk.core.model.PayabliDeclineException
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket

/** The 1.3 wiring: transport, configuration and decode failures all surface as a [PayabliException]. */
class PayabliServiceErrorTest {
    @Serializable
    private class Payload(
        val paymentTransId: String,
    )

    private val sink = RecordingLogSink()

    private fun service(baseUrl: String) =
        PayabliService.create(
            baseUrl = baseUrl,
            auth = testAuth(),
            logger = DefaultPayabliLogger(LogCategory.NETWORK, sink),
        )

    /** A port that accepted then closed, so a connection attempt is refused rather than hanging. */
    private fun closedPortBaseUrl(): String {
        val port = ServerSocket(0).use { it.localPort }
        return "http://127.0.0.1:$port"
    }

    private suspend fun failureFrom(block: suspend () -> Unit): PayabliException {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
        return thrown as PayabliException
    }

    @Test
    fun `a refused connection becomes a network error carrying the IOException`() =
        runTest {
            val failure =
                failureFrom {
                    service(closedPortBaseUrl()).execute(PayabliRequest(HttpMethod.GET, "/api/ping"))
                }

            assertEquals(PayabliErrorCode.NETWORK_ERROR, failure.code)
            assertTrue("cause was ${failure.cause}", failure.cause is IOException)
        }

    @Test
    fun `a malformed base URL becomes invalid configuration, not a network error`() =
        runTest {
            // Raised while constructing, so a configuration mistake surfaces before the first payment.
            val failure =
                failureFrom {
                    service("not a url at all").execute(PayabliRequest(HttpMethod.GET, "/api/ping"))
                }

            assertEquals(PayabliErrorCode.INVALID_CONFIGURATION, failure.code)
        }

    @Test
    fun `an unencodable path also becomes invalid configuration`() =
        runTest {
            LoopbackServer().use { server ->
                val failure =
                    failureFrom {
                        // A raw space is illegal in a path; the caller is expected to have encoded it.
                        service(server.baseUrl).execute(PayabliRequest(HttpMethod.GET, "/api/a b"))
                    }

                assertEquals(PayabliErrorCode.INVALID_CONFIGURATION, failure.code)
            }
        }

    @Test
    fun `a rejected path never travels out inside the cause`() =
        runTest {
            LoopbackServer().use { server ->
                // Both shapes resolve can reject, because they throw different types and only one echoes
                // its input: a raw space raises URISyntaxException, which quotes the whole reference,
                // while an authority fails our own require, whose message is path-free. The invariant has
                // to hold either way, because the type is not a reliable signal of which is which.
                val identifier = "9999999999"
                val cases =
                    listOf(
                        PayabliRequest(HttpMethod.GET, "/api/v2/MoneyIn/capture/$identifier/a b"),
                        PayabliRequest(HttpMethod.GET, "//attacker.example/$identifier"),
                    )

                for (request in cases) {
                    val failure = failureFrom { service(server.baseUrl).execute(request) }

                    assertEquals(PayabliErrorCode.INVALID_CONFIGURATION, failure.code)
                    assertFalse(
                        "the identifier reached the cause for path ${request.route ?: "n/a"}",
                        failure.stackTraceToString().contains(identifier),
                    )
                }
            }
        }

    @Test
    fun `a non-envelope body on the decoding overload becomes a decoding error`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "<html>not json</html>")

                val failure =
                    failureFrom {
                        service(server.baseUrl).execute(
                            PayabliRequest(HttpMethod.POST, "/api/v2/MoneyIn/initiate"),
                            Payload.serializer(),
                        )
                    }

                assertEquals(PayabliErrorCode.DECODING_ERROR, failure.code)
                // The failure type survives, so the cause still says what went wrong.
                assertTrue("cause was ${failure.cause}", failure.cause is RedactedCause)
                assertTrue(
                    "the cause should name the original type, got ${failure.cause?.message}",
                    failure.cause?.message?.contains("serialization", ignoreCase = true) == true,
                )
            }
        }

    @Test
    fun `a decoding failure never carries the response body out with it`() =
        runTest {
            LoopbackServer().use { server ->
                // Stands in for anything a malformed response could echo. kotlinx.serialization appends
                // the input it could not parse to its own message, and a host crash reporter renders the
                // whole cause chain, which this SDK cannot scrub.
                val sentinel = "SENTINEL-PAYLOAD-CONTENT"
                // Non-JSON on purpose, standing in for a proxy error page that echoes request data.
                // Only that path leaks: kotlinx appends `JSON input: <body>` to a JsonDecodingException,
                // whereas well-formed JSON missing a field raises MissingFieldException, which names the
                // field and echoes nothing. A well-formed body here would pass with or without the fix.
                server.respondWith(200, "<html>error processing $sentinel</html>")

                val failure =
                    failureFrom {
                        service(server.baseUrl).execute(
                            PayabliRequest(HttpMethod.POST, "/api/v2/MoneyIn/initiate"),
                            Payload.serializer(),
                        )
                    }

                assertEquals(PayabliErrorCode.DECODING_ERROR, failure.code)
                val causeMessage = failure.cause?.message.orEmpty()
                assertFalse("message leaked the body", failure.message.orEmpty().contains(sentinel))
                assertFalse("cause message leaked the body", causeMessage.contains(sentinel))
                // The whole rendered chain, which is what a crash reporter writes.
                assertFalse("stack trace leaked the body", failure.stackTraceToString().contains(sentinel))
            }
        }

    @Test
    fun `the decoding overload maps the status before it tries to decode`() =
        runTest {
            LoopbackServer().use { server ->
                // An HTML error page on a 402 must become a decline, not a decode failure.
                server.respondWith(402, "<html>502 Bad Gateway</html>")

                val failure =
                    failureFrom {
                        service(server.baseUrl).execute(
                            PayabliRequest(HttpMethod.POST, "/api/pay"),
                            Payload.serializer(),
                        )
                    }

                assertTrue(failure is PayabliDeclineException)
                assertEquals(PayabliErrorCode.PAYMENT_DECLINED, failure.code)
            }
        }

    @Test
    fun `a 402 envelope body on the decoding overload becomes a typed decline`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(402, """{"code":"D0329","reason":"Insufficient funds"}""")

                val failure =
                    failureFrom {
                        service(server.baseUrl).execute(
                            PayabliRequest(HttpMethod.POST, "/api/pay"),
                            Payload.serializer(),
                        )
                    }

                assertEquals("D0329", (failure as PayabliDeclineException).rawCode)
            }
        }

    @Test
    fun `the raw execute still returns a non-2xx rather than throwing`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(500, "boom")

                val response = service(server.baseUrl).execute(PayabliRequest(HttpMethod.GET, "/api/x"))

                // Only the decoding overload maps; the raw seam stays uninterpreted.
                assertEquals(500, response.statusCode)
            }
        }

    @Test
    fun `a failure logs the error code and never the resolved path`() =
        runTest {
            failureFrom {
                service(closedPortBaseUrl()).execute(
                    PayabliRequest(HttpMethod.GET, "/api/v2/MoneyIn/capture/9999999999"),
                )
            }

            val logged = sink.records.joinToString("\n") { it.message }
            assertTrue(logged.contains("errorCode=NETWORK_ERROR"))
            assertFalse(logged.contains("9999999999"))
        }
}
