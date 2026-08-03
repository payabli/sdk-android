package com.payabli.sdk.core.network.impl

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
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
 * local step and a regression here will not turn a pull request red.
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
     * The bound is derived from the budget and the stall rather than written as a number, so it states the
     * claim instead of approximating it: the call ended nearer the budget it was given than the stall it was
     * cut out of. Any machine fast enough to run the test can show that.
     */
    @Test
    fun theCallBudgetTearsTheSocketDownRatherThanWaitingOutTheStall() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(200, "").stallBeforeResponding(STALL_MILLIS)

                val startedAt = System.currentTimeMillis()
                val thrown =
                    runCatching {
                        service(server, callTimeout = CALL_BUDGET_MILLIS.milliseconds)
                            .execute(PayabliRequest(HttpMethod.GET, "/api/ping"))
                    }.exceptionOrNull()
                val elapsed = System.currentTimeMillis() - startedAt

                assertTrue("expected a PayabliException, got $thrown", thrown is PayabliException)
                assertEquals(PayabliErrorCode.NETWORK_ERROR, (thrown as PayabliException).code)
                // The request arrived, so the budget ended a call in flight rather than one that never began.
                assertEquals("/api/ping", server.onlyRequest.path)
                assertTrue(
                    "the call waited out the stall instead of being cut off: ${elapsed}ms of a " +
                        "${STALL_MILLIS}ms stall, over a ${CUTOFF_MILLIS}ms bound on a " +
                        "${CALL_BUDGET_MILLIS}ms budget",
                    elapsed < CUTOFF_MILLIS,
                )
            }
        }

    private companion object {
        /** The budget under test, named so the bound below is derived from it rather than tracking it. */
        const val CALL_BUDGET_MILLIS = 200L

        /** Well above the budget and well below the socket read timeout, so the budget is provably
         * what fired. */
        const val STALL_MILLIS = 800L

        /**
         * The midpoint, which is what "nearer the budget than the stall" means.
         *
         * A tighter bound catches nothing extra, since the behaviour it guards against is waiting out the
         * whole stall, and it spends slack that a loaded emulator needs. On a hosted runner that slack is
         * the difference between a signal and a retry habit.
         */
        const val CUTOFF_MILLIS = (CALL_BUDGET_MILLIS + STALL_MILLIS) / 2
    }
}
