package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.auth.testAuth
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.testutils.network.LoopbackServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

private const val OK_BODY = "{}"
private const val UNAUTHORIZED = 401
private const val OK = 200

/** Read off the wire, because what the transport sends is the claim that matters. */
class ClientHeaderDecorationTest {
    private val facts =
        ClientFacts(
            sdkVersion = "0.1.0",
            osVersion = "15",
            hardware = "SM-S908U1",
            deviceId = { "0123456789abcdef0123456789abcdef" },
            locale = { "en-US" },
        )
    private val expected = ClientHeader.valueOf(facts)

    private fun service(
        server: LoopbackServer,
        auth: PayabliAuth = testAuth(),
    ) = PayabliService.create(
        baseUrl = server.baseUrl,
        auth = auth,
        dispatcher = Dispatchers.IO,
        logger = DefaultSdkLogger(LogCategory.NETWORK, RecordingLogSink()),
        client = facts,
    )

    private fun get(headers: Map<String, String> = emptyMap()) =
        PayabliRequest(HttpMethod.GET, "/api/ping", route = "/api/ping", headers = headers)

    @Test
    fun `every request carries the client header`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(OK, OK_BODY)

                service(server).execute(get())

                assertEquals(expected, server.onlyRequest.header(CLIENT_HEADER))
            }
        }

    @Test
    fun `a replay after a 401 carries it again`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondInOrder(UNAUTHORIZED to OK_BODY, OK to OK_BODY)
                val auth = testAuth(tokenProvider = { "fresh-token" })

                AuthenticatedTransport(service(server, auth), auth).execute(get())

                assertEquals("the 401 was not replayed", 2, server.recorded.size)
                server.recorded.forEach { assertEquals(expected, it.header(CLIENT_HEADER)) }
            }
        }

    @Test
    fun `a caller's own client value is not what reaches the wire`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(OK, OK_BODY)

                service(server).execute(get(mapOf(CLIENT_HEADER to "caller-supplied")))

                assertNotEquals("caller-supplied", server.onlyRequest.header(CLIENT_HEADER))
            }
        }

    @Test
    fun `the activation id header a caller sets reaches the wire unchanged beside it`() =
        runTest {
            LoopbackServer().use { server ->
                server.respondWith(OK, OK_BODY)

                service(server).execute(get(mapOf("X-Device-Id" to "activation-id")))

                assertEquals("activation-id", server.onlyRequest.header("X-Device-Id"))
                assertEquals(expected, server.onlyRequest.header(CLIENT_HEADER))
            }
        }
}
