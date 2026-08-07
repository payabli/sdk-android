package com.payabli.example.app.net

import com.payabli.example.app.config.TokenHostSource
import com.payabli.example.app.config.TokenServerTarget
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The socket path, against a real server on the loopback interface.
 *
 * A fake client would prove the wording and nothing else. What is worth covering here is what
 * `HttpURLConnection` actually does with each answer, and the two cases the probe types exist to
 * tell apart: a bad status, and a good status carrying a body this route does not return.
 *
 * `com.sun.net.httpserver` ships with the JDK, so this adds no dependency, and the module's unit
 * tests run on the host JVM where it is present.
 */
class TokenServerClientTest {
    private var server: HttpServer? = null

    @After
    fun stopServer() {
        server?.stop(0)
        server = null
    }

    /** Returns the target pointing at it. Port 0, so parallel runs cannot collide. */
    private fun serve(handle: (HttpExchange) -> Unit): TokenServerTarget {
        val started =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/", handle)
                start()
            }
        server = started
        return TokenServerTarget(
            "http://127.0.0.1:${started.address.port}",
            TokenHostSource.Emulator,
        )
    }

    private fun HttpExchange.reply(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun client(target: TokenServerTarget) =
        // Unconfined would let the request run on the test thread, and this one really does block.
        TokenServerClient(target, ioDispatcher = Dispatchers.IO)

    @Test
    fun `a token in the body is reported without the token`() =
        runTest {
            val target = serve { it.reply(200, """{"accessToken":"super-secret-value"}""") }
            val probe = client(target).probeAccessToken()

            assertEquals(TokenServerProbe.Ok("returned a token"), probe)
            assertTrue(
                "the token reached the screen",
                !probe.displayText(TokenServerProbe.TOKEN_LABEL).contains("super-secret-value"),
            )
        }

    @Test
    fun `a success carrying no token is malformed, not an HTTP status`() =
        runTest {
            // The defect this separates out: folding it into HttpStatus rendered "returned HTTP
            // 200", which points a reader at the one thing that was right.
            val target = serve { it.reply(200, """{"somethingElse":true}""") }
            val probe = client(target).probeAccessToken()

            assertTrue("reported as a status", probe is TokenServerProbe.Malformed)
            assertTrue(probe.displayText(TokenServerProbe.TOKEN_LABEL).contains("no token"))
        }

    @Test
    fun `a body that is not JSON is malformed too`() =
        runTest {
            val target = serve { it.reply(200, "not json at all") }
            assertTrue(client(target).probeAccessToken() is TokenServerProbe.Malformed)
        }

    @Test
    fun `a failing status is reported with its code`() =
        runTest {
            val target = serve { it.reply(503, "down") }
            assertEquals(TokenServerProbe.HttpStatus(503), client(target).probeAccessToken())
        }

    @Test
    fun `health only needs the status`() =
        runTest {
            val target = serve { it.reply(200, "") }
            assertEquals(TokenServerProbe.Ok("healthy"), client(target).probeHealth())
        }

    @Test
    fun `nothing listening is unreachable, and says so in the transport's words`() =
        runTest {
            // Port 1 on loopback: privileged, and nothing in this test ever binds it.
            val target = TokenServerTarget("http://127.0.0.1:1", TokenHostSource.Emulator)
            val probe = client(target).probeHealth()

            assertTrue(probe is TokenServerProbe.Unreachable)
            assertTrue(
                "the reason is missing",
                probe.displayText(TokenServerProbe.HEALTH_LABEL).length >
                    "✗ ${TokenServerProbe.HEALTH_LABEL} unreachable: ".length,
            )
        }
}
