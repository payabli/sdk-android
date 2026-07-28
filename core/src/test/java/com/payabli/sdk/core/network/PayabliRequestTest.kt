package com.payabli.sdk.core.network

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayabliRequestTest {
    @Serializable
    private class Body(
        val amount: Int,
        val note: String? = null,
    )

    private fun jsonRequest(
        body: Body,
        headers: Map<String, String> = emptyMap(),
    ) = PayabliRequest.json(HttpMethod.POST, "/pay", body, Body.serializer(), headers = headers)

    @Test
    fun `a bare request defaults to no query, no headers and no body`() {
        val request = PayabliRequest(HttpMethod.GET, "/ping")
        assertTrue(request.query.isEmpty())
        assertTrue(request.headers.isEmpty())
        assertNull(request.body)
    }

    @Test
    fun `json adds the content type header and encodes the body`() {
        val request = jsonRequest(Body(amount = 500))
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("""{"amount":500}""", request.body?.toString(Charsets.UTF_8))
    }

    @Test
    fun `json preserves caller headers alongside the content type`() {
        val request = jsonRequest(Body(amount = 1), headers = mapOf("X-Pyb-Client" to "android/0.1.0"))
        assertEquals("android/0.1.0", request.headers["X-Pyb-Client"])
        assertEquals("application/json", request.headers["Content-Type"])
    }

    @Test
    fun `a caller supplied content type is overridden rather than duplicated`() {
        val request = jsonRequest(Body(amount = 1), headers = mapOf("Content-Type" to "text/plain"))
        assertEquals("application/json", request.headers["Content-Type"])
    }

    @Test
    fun `null body fields are omitted from the wire body`() {
        // explicitNulls = false, so an absent optional does not become "note": null.
        assertEquals("""{"amount":42}""", jsonRequest(Body(amount = 42)).body?.toString(Charsets.UTF_8))
    }

    @Test
    fun `query keys may repeat because query is an ordered list`() {
        val request = PayabliRequest(HttpMethod.GET, "/list", query = listOf("id" to "1", "id" to "2"))
        assertEquals(listOf("1", "2"), request.query.filter { it.first == "id" }.map { it.second })
    }

    @Test
    fun `toString carries the method but never headers, body, or the resolved path`() {
        val request = jsonRequest(Body(amount = 999), headers = mapOf("Authorization" to "Bearer secret-value"))
        val rendered = request.toString()
        assertTrue(rendered.contains("POST"))
        assertFalse(rendered.contains("Bearer"))
        assertFalse(rendered.contains("secret-value"))
        assertFalse(rendered.contains("999"))
        // The path may embed an identifier and toString reaches exception messages the logger cannot redact.
        assertFalse(rendered.contains("/pay"))
        assertTrue(rendered.contains("[REDACTED]"))
    }

    @Test
    fun `toString renders the route template when one was supplied`() {
        val request =
            PayabliRequest(
                method = HttpMethod.GET,
                path = "/api/v2/MoneyIn/capture/9999999999",
                route = "/api/v2/MoneyIn/capture/{id}",
            )
        val rendered = request.toString()
        assertTrue(rendered.contains("/api/v2/MoneyIn/capture/{id}"))
        assertFalse(rendered.contains("9999999999"))
    }
}
