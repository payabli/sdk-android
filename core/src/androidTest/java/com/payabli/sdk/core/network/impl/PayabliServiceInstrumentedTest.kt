package com.payabli.sdk.core.network.impl

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultPayabliLogger
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The transport against Android's `HttpURLConnection`, which is a different implementation from the JVM's
 * and not merely the same code on a slower machine.
 *
 * Two behaviours here cannot be shown off-device at all, because the JVM implementation does not have them:
 * transparent gzip and `PATCH` acceptance. Both are asserted as promises in `PayabliService`, so without this
 * file those comments describe coverage that does not exist. The third, the call budget's socket teardown, is
 * covered on the JVM too and is repeated here because the mechanism is platform-specific.
 *
 * **CI does not run this.** The workflow has no emulator, so `:core:connectedAndroidTest` is a deliberate
 * local step and a regression here will not turn a pull request red. PLA-2306 adds a manual and nightly
 * emulator job, deliberately not a required per-PR check.
 */
@RunWith(AndroidJUnit4::class)
class PayabliServiceInstrumentedTest {
    /** As on the JVM: injected so the redaction path stays under test rather than writing to logcat. */
    private val sink = RecordingLogSink()

    private fun service(
        server: LoopbackServer,
        callTimeout: Duration = PayabliService.DEFAULT_CALL_TIMEOUT,
    ) = PayabliService.create(
        baseUrl = server.baseUrl,
        auth = testAuth(),
        logger = DefaultPayabliLogger(LogCategory.NETWORK, sink),
        callTimeout = callTimeout,
    )

    /**
     * `PATCH` reaches the wire here, where the JVM rejects the verb at `setRequestMethod`.
     *
     * The direct counterpart is the JVM's `a method the platform rejects becomes invalid configuration`,
     * which asserts the mapping of that rejection. The pair is the point: the same request is a
     * configuration error there and a completed round trip here, so neither test alone describes the verb.
     */
    @Test
    fun patchReachesTheWireOnAndroid() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, """{"ok":true}""")

                val response =
                    service(server).execute(
                        PayabliRequest(
                            HttpMethod.PATCH,
                            "/api/x",
                            body = """{"amount":1}""".toByteArray(Charsets.UTF_8),
                        ),
                    )

                assertEquals(200, response.statusCode)
                // The verb survived as itself: no silent rewrite to POST, which is the usual workaround and
                // would make a non-idempotent route replayable under a policy that reads the method.
                assertEquals("PATCH", server.onlyRequest.method)
                assertEquals("""{"amount":1}""", server.onlyRequest.body)
            }
        }

    /**
     * The transport leaves `Accept-Encoding` unset so the platform owns compression end to end. This asserts
     * both halves of that bargain, because only the pair shows the transport is not doing the work itself.
     */
    @Test
    fun gzipIsNegotiatedAndDecompressedByThePlatform() =
        runTest {
            val payload = """{"paymentTransId":"abc123","note":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
            LoopbackServer().use { server ->
                server.respondWith(200, payload).gzipBody()

                val response = service(server).execute(PayabliRequest(HttpMethod.GET, "/api/ping"))

                // Negotiated by the platform, since the transport never sets this header.
                val offered = server.onlyRequest.header("Accept-Encoding")
                assertNotNull("the platform sent no Accept-Encoding, so gzip was never negotiated", offered)
                assertTrue("expected gzip to be offered, got $offered", offered!!.contains("gzip"))

                // Decompressed before the transport saw it: the body is the plaintext, not the deflated bytes.
                assertEquals(200, response.statusCode)
                assertEquals(payload, response.bodyAsText())
                // And the bound the transport reads is the decompressed size, so a Content-Length counting
                // compressed bytes was not mistaken for the body length.
                assertEquals(payload.toByteArray(Charsets.UTF_8).size, response.body.size)
                // The load-bearing assertion, and the reason the harness counts wire bytes at all. Without
                // it this test passes with compression switched off: an uncompressed round trip satisfies
                // every assertion above, so nothing so far distinguishes decompression from never having
                // compressed. Fewer bytes on the wire than in the result is what only compression explains.
                assertTrue(
                    "the body was not compressed on the wire, so decompression is unproven: " +
                        "${server.lastResponseBodyBytes} bytes sent for ${response.body.size} received",
                    server.lastResponseBodyBytes < response.body.size,
                )
            }
        }

    /**
     * The whole-call budget, on the implementation whose `disconnect()` behaviour it depends on.
     *
     * Asserts elapsed time against the stall rather than a fixed number, so slow hardware cannot make it
     * flaky: what is being claimed is that the call ended nearer its budget than the stall, and any machine
     * fast enough to run the test at all can show that.
     */
    @Test
    fun theCallBudgetTearsTheSocketDownRatherThanWaitingOutTheStall() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "").stallBeforeResponding(STALL_MILLIS)

                val startedAt = System.currentTimeMillis()
                val thrown =
                    runCatching {
                        service(server, callTimeout = 200.milliseconds)
                            .execute(PayabliRequest(HttpMethod.GET, "/api/ping"))
                    }.exceptionOrNull()
                val elapsed = System.currentTimeMillis() - startedAt

                assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
                assertEquals(PayabliErrorCode.NETWORK_ERROR, (thrown as PayabliException).code)
                // The request arrived, so the budget ended a call in flight rather than one that never began.
                assertEquals("/api/ping", server.onlyRequest.path)
                assertTrue(
                    "the call waited out the stall instead of being cut off: " +
                        "${elapsed}ms of a ${STALL_MILLIS}ms stall",
                    elapsed < STALL_MILLIS / 2,
                )
            }
        }

    private companion object {
        /** Well above the 200ms budget under test and well below the socket read timeout, so the
         * budget is provably what fired. */
        const val STALL_MILLIS = 800L
    }
}
