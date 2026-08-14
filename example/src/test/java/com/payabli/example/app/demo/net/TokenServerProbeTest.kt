package com.payabli.example.app.demo.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the mapping from outcome to on-screen line, which is where the wording lives.
 *
 * The socket work is [TokenServerClientTest]'s, against a real server on the loopback interface.
 * `:core`'s own harness is not reused: its fixtures are `internal`, and widening a published
 * security SDK's API to suit a sample app's test layout is not a trade worth making. The JDK's HTTP
 * server needs neither.
 */
class TokenServerProbeTest {
    @Test
    fun `a healthy server reads as a tick`() {
        assertEquals(
            "✓ Local token server healthy",
            TokenServerProbe.Ok("healthy").displayText(TokenServerProbe.HEALTH_LABEL),
        )
    }

    @Test
    fun `a token that arrived reads as a tick and does not name the token`() {
        val text = TokenServerProbe.Ok("returned a token").displayText(TokenServerProbe.TOKEN_LABEL)
        assertEquals("✓ Token endpoint returned a token", text)
    }

    @Test
    fun `a non success status names the code`() {
        assertEquals(
            "✗ Local token server returned HTTP 502",
            TokenServerProbe.HttpStatus(502).displayText(TokenServerProbe.HEALTH_LABEL),
        )
    }

    @Test
    fun `an unreachable server carries the transport's own words`() {
        assertEquals(
            "✗ Token endpoint unreachable: Connection refused",
            TokenServerProbe.Unreachable("Connection refused").displayText(TokenServerProbe.TOKEN_LABEL),
        )
    }

    @Test
    fun `every outcome is marked with a glyph, so the result survives a monochrome screenshot`() {
        val outcomes =
            listOf(
                TokenServerProbe.Ok("healthy"),
                TokenServerProbe.HttpStatus(500),
                TokenServerProbe.Unreachable("timeout"),
            )
        outcomes.forEach { outcome ->
            val text = outcome.displayText("Endpoint")
            assertEquals(
                "$outcome does not start with a status glyph",
                true,
                text.startsWith("✓ ") || text.startsWith("✗ "),
            )
        }
    }
}
