package com.payabli.sdk.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayabliResponseTest {
    @Test
    fun `two hundred range is successful and everything else is not`() {
        assertTrue(PayabliResponse(200).isSuccessful)
        assertTrue(PayabliResponse(201).isSuccessful)
        assertTrue(PayabliResponse(299).isSuccessful)
        assertFalse(PayabliResponse(199).isSuccessful)
        assertFalse(PayabliResponse(300).isSuccessful)
        assertFalse(PayabliResponse(402).isSuccessful)
        assertFalse(PayabliResponse(500).isSuccessful)
    }

    @Test
    fun `header lookup ignores case because HttpURLConnection does not normalise it`() {
        val response = PayabliResponse(200, headers = mapOf("Retry-After" to "30"))
        assertEquals("30", response.header("Retry-After"))
        assertEquals("30", response.header("retry-after"))
        assertEquals("30", response.header("RETRY-AFTER"))
        assertNull(response.header("Retry-Before"))
    }

    @Test
    fun `an empty body defaults to zero bytes and decodes to an empty string`() {
        val response = PayabliResponse(204)
        assertEquals(0, response.body.size)
        assertEquals("", response.bodyAsText())
    }

    @Test
    fun `body decodes as utf-8`() {
        val response = PayabliResponse(200, body = "éç ok".toByteArray(Charsets.UTF_8))
        assertEquals("éç ok", response.bodyAsText())
    }

    @Test
    fun `toString carries the status and body size but never the body or headers`() {
        val response =
            PayabliResponse(
                statusCode = 200,
                headers = mapOf("Set-Cookie" to "session=secret-value"),
                body = """{"accountNumber":"0000"}""".toByteArray(Charsets.UTF_8),
            )
        val rendered = response.toString()
        assertTrue(rendered.contains("200"))
        assertTrue(rendered.contains("bodyBytes"))
        assertFalse(rendered.contains("secret-value"))
        assertFalse(rendered.contains("accountNumber"))
    }
}
