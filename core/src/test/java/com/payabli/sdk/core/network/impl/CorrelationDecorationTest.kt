package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.testutils.auth.testAuth
import com.payabli.sdk.testutils.network.LoopbackServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.UUID

private const val HEADER = "X-Correlation-ID"
private const val OK_BODY = "{}"
private const val UNAUTHORIZED = 401
private const val OK = 200
private const val VERSION_7 = 7

/**
 * Read off the wire rather than off the request object, because what a decoration returns and what the
 * transport sends are two different claims and only the second one matters.
 */
class CorrelationDecorationTest {
    private val sink = RecordingLogSink()

    private fun service(
        server: LoopbackServer,
        auth: PayabliAuth = testAuth(),
    ) = PayabliService.create(
        baseUrl = server.baseUrl,
        auth = auth,
        dispatcher = Dispatchers.IO,
        logger = DefaultSdkLogger(LogCategory.NETWORK, sink),
    )

    private fun get(headers: Map<String, String> = emptyMap()) =
        PayabliRequest(HttpMethod.GET, "/api/ping", route = "/api/ping", headers = headers)

    /**
     * Every wire read goes through this, so an absent header fails here.
     *
     * Reading it as a nullable and comparing two of them would let a regression that drops the header from
     * one request of two stay green, because a null and a UUID are unequal.
     */
    private fun LoopbackServer.Recorded.correlationId(): String {
        val value = header(HEADER)
        assertNotNull("no correlation header reached the wire", value)
        return requireNotNull(value)
    }

    private fun LoopbackServer.correlationIds(): List<String> = recorded.map { it.correlationId() }

    @Test
    fun `every request carries a version 7 correlation id`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(OK, OK_BODY)

                service(server).execute(get())

                val sent = server.onlyRequest.correlationId()
                assertEquals(VERSION_7, UUID.fromString(sent).version())
            }
        }

    @Test
    fun `two requests carry two different correlation ids`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondInOrder(OK to OK_BODY, OK to OK_BODY)
                val transport = service(server)

                transport.execute(get())
                transport.execute(get())

                val ids = server.correlationIds()
                assertEquals(2, ids.size)
                assertNotEquals(ids[0], ids[1])
            }
        }

    /**
     * The replay is a second physical request, so it is a second correlation id. This is the case the
     * architecture answers rather than the decoration: the chain is re-applied on every entry to
     * [PayabliService.execute], and the layer that resends wraps it. It fails if that ever inverts.
     */
    @Test
    fun `a replay after a 401 carries a different correlation id from the attempt it replaced`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondInOrder(UNAUTHORIZED to OK_BODY, OK to OK_BODY)
                val auth = testAuth(tokenProvider = { "fresh-token" })

                AuthenticatedTransport(service(server, auth), auth).execute(get())

                val ids = server.correlationIds()
                assertEquals("the 401 was not replayed", 2, ids.size)
                assertNotEquals(ids[0], ids[1])
            }
        }

    /**
     * A caller cannot supply its own value: `withHeaders` removes a differently-cased key rather than
     * shadowing it, which `PayabliRequestDecorationTest` asserts on the request itself. Not re-asserted off
     * the wire here, because `setRequestProperty` collapses a duplicate case-insensitively and iteration
     * order then picks the survivor, so a wire-level version of this passes even when the removal is gone.
     */
    @Test
    fun `a caller's own correlation value is not what reaches the wire`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(OK, OK_BODY)

                service(server).execute(get(mapOf(HEADER to "caller-supplied")))

                assertNotEquals("caller-supplied", server.onlyRequest.correlationId())
            }
        }
}
