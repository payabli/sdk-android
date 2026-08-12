package com.payabli.sdk.core.network.impl

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration

/**
 * The transport against Android's `HttpURLConnection`, which is a different implementation from the JVM's
 * and not merely the same code on a slower machine.
 *
 * Two behaviours here cannot be shown off-device at all, because the JVM implementation does not have them:
 * transparent gzip and `PATCH` acceptance. Both are asserted as promises in `PayabliService`, so without this
 * file those comments describe coverage that does not exist. The third, the call budget's socket teardown, is
 * covered on the JVM too and is repeated here because the mechanism is platform-specific.
 *
 * **No pull request runs this.** The per-PR workflow has no emulator, so a regression here will not turn a
 * pull request red. The nightly workflow does run `:core:connectedAndroidTest` on one, deliberately as a
 * non-required check, so the gap is same-day feedback rather than coverage.
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
        dispatcher = Dispatchers.IO,
        logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
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
     * The claim, the bound and the repetition live in [assertTheCallBudgetCutsTheCallOutOfTheStall], beside
     * the JVM copy of the same claim, so the two tiers cannot drift into asserting different things about
     * one mechanism. Only the transport differs, which is the point of running it here at all.
     */
    @Test
    fun theCallBudgetTearsTheSocketDownRatherThanWaitingOutTheStall() =
        runTest {
            assertTheCallBudgetCutsTheCallOutOfTheStall { server, budget ->
                service(server, callTimeout = budget)
            }
        }
}
