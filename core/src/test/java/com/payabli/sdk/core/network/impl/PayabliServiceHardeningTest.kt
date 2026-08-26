package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.testutils.auth.testAuth
import com.payabli.sdk.testutils.network.LoopbackServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The origin pin and the response ceiling. Every test here fails against the version of `PayabliService`
 * that shipped in the first commit.
 */
class PayabliServiceHardeningTest {
    private val sink = RecordingLogSink()

    private fun service(
        baseUrl: String,
        maxResponseBytes: Long = PayabliService.DEFAULT_MAX_RESPONSE_BYTES,
    ) = PayabliService.create(
        auth = testAuth(),
        baseUrl = baseUrl,
        dispatcher = Dispatchers.IO,
        logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
        maxResponseBytes = maxResponseBytes,
    )

    private suspend fun failureFrom(block: suspend () -> Unit): PayabliException {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
        return thrown as PayabliException
    }

    @Test
    fun `an absolute path cannot replace the configured origin`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "")

                // URI.resolve returns an already-absolute reference unchanged, so without the pin this
                // would send the request, and any headers a decoration attached, to another host.
                val failure =
                    failureFrom {
                        service(server.baseUrl).execute(
                            PayabliRequest(HttpMethod.GET, "https://attacker.example/x"),
                        )
                    }

                assertEquals(PayabliErrorCode.INVALID_CONFIGURATION, failure.code)
                assertTrue("nothing may reach the server", server.recorded.isEmpty())
            }
        }

    @Test
    fun `a scheme-relative path cannot replace the authority`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "")

                val failure =
                    failureFrom {
                        service(server.baseUrl).execute(PayabliRequest(HttpMethod.GET, "//attacker.example/x"))
                    }

                assertEquals(PayabliErrorCode.INVALID_CONFIGURATION, failure.code)
                assertTrue(server.recorded.isEmpty())
            }
        }

    @Test
    fun `a path cannot escape the base path with dot-dot segments`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "")

                val failure =
                    failureFrom {
                        service(server.baseUrl + "/api/").execute(
                            PayabliRequest(HttpMethod.GET, "../../etc/passwd"),
                        )
                    }

                assertEquals(PayabliErrorCode.INVALID_CONFIGURATION, failure.code)
                assertTrue(server.recorded.isEmpty())
            }
        }

    @Test
    fun `an ordinary relative path still resolves`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "ok")

                val response = service(server.baseUrl).execute(PayabliRequest(HttpMethod.GET, "/api/ping"))

                assertEquals(200, response.statusCode)
                assertEquals("/api/ping", server.onlyRequest.path)
            }
        }

    @Test
    fun `a body over the ceiling fails instead of growing without limit`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "x".repeat(2_048))

                val failure =
                    failureFrom {
                        service(server.baseUrl, maxResponseBytes = 1_024).execute(
                            PayabliRequest(HttpMethod.GET, "/api/big"),
                        )
                    }

                assertEquals(PayabliErrorCode.NETWORK_ERROR, failure.code)
                assertEquals(PayabliService.REASON_RESPONSE_TOO_LARGE, failure.reason)
            }
        }

    @Test
    fun `a body exactly at the ceiling succeeds`() =
        runTest {
            LoopbackServer().use { server ->
                val body = "x".repeat(1_024)
                server.respondWith(200, body)

                val response =
                    service(server.baseUrl, maxResponseBytes = 1_024)
                        .execute(PayabliRequest(HttpMethod.GET, "/api/exact"))

                // The boundary is inclusive, so an exactly-at-limit body is not a failure.
                assertEquals(body, response.bodyAsText())
            }
        }

    @Test
    fun `a body one byte over the ceiling fails`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "x".repeat(1_025))

                val failure =
                    failureFrom {
                        service(server.baseUrl, maxResponseBytes = 1_024).execute(
                            PayabliRequest(HttpMethod.GET, "/api/over"),
                        )
                    }

                assertEquals(PayabliErrorCode.NETWORK_ERROR, failure.code)
            }
        }
}
