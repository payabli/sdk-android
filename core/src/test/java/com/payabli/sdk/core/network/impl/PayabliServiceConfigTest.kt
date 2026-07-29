package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultPayabliLogger
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Configuration failures must be typed, not escape as a raw platform exception. */
class PayabliServiceConfigTest {
    private val sink = RecordingLogSink()

    private fun failureFrom(block: () -> Unit): PayabliException {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
        return thrown as PayabliException
    }

    private fun create(baseUrl: String) =
        PayabliService.create(
            baseUrl = baseUrl,
            auth = testAuth(),
            logger = DefaultPayabliLogger(LogCategory.NETWORK, sink),
        )

    @Test
    fun `a non-http scheme is rejected at construction, not at the connection cast`() {
        // file:///tmp parses as a URI, then fails the HttpURLConnection cast with a ClassCastException that
        // no mapping would catch.
        assertEquals(PayabliErrorCode.INVALID_CONFIGURATION, failureFrom { create("file:///tmp") }.code)
    }

    @Test
    fun `a base URL with no host is rejected`() {
        assertEquals(PayabliErrorCode.INVALID_CONFIGURATION, failureFrom { create("https:///nohost") }.code)
    }

    @Test
    fun `a relative base URL is rejected`() {
        assertEquals(PayabliErrorCode.INVALID_CONFIGURATION, failureFrom { create("api.payabli.com") }.code)
    }

    @Test
    fun `http and https are both accepted`() {
        create("http://127.0.0.1:8080")
        create("https://api-sandbox.payabli.com")
    }

    @Test
    fun `a method the platform rejects becomes invalid configuration, not a network error`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "")

                // PATCH is accepted by Android's implementation and rejected by the JVM's, so on the JVM
                // this asserts the mapping. The route itself is exercised on a device.
                val thrown =
                    runCatching {
                        create(server.baseUrl).execute(PayabliRequest(HttpMethod.PATCH, "/api/x"))
                    }.exceptionOrNull()
                assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
                val failure = thrown as PayabliException

                assertEquals(PayabliErrorCode.INVALID_CONFIGURATION, failure.code)
                assertEquals(PayabliService.REASON_METHOD_UNSUPPORTED, failure.reason)
            }
        }
}
