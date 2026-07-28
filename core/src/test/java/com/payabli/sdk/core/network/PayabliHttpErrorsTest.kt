package com.payabli.sdk.core.network

import com.payabli.sdk.core.model.PayabliDeclineException
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.model.PayabliServerException
import com.payabli.sdk.core.model.PayabliValidationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ticket's acceptance criterion: each mapped status produces the expected typed error.
 */
class PayabliHttpErrorsTest {
    private fun response(
        statusCode: Int,
        body: String = "",
    ) = PayabliResponse(statusCode, emptyMap(), body.toByteArray(Charsets.UTF_8))

    private fun map(
        statusCode: Int,
        body: String = "",
    ): PayabliException? = PayabliHttpErrors.from(response(statusCode, body))

    @Test
    fun `every 2xx maps to null`() {
        listOf(200, 201, 202, 204, 299).forEach { assertNull("status $it", map(it)) }
    }

    @Test
    fun `400 maps to a validation error`() {
        val mapped = map(400)
        assertTrue(mapped is PayabliValidationException)
        assertEquals(PayabliErrorCode.VALIDATION_ERROR, mapped?.code)
    }

    @Test
    fun `401 maps to token expired`() {
        assertEquals(PayabliErrorCode.TOKEN_EXPIRED, map(401)?.code)
    }

    @Test
    fun `402 maps to a decline`() {
        val mapped = map(402)
        assertTrue(mapped is PayabliDeclineException)
        assertEquals(PayabliErrorCode.PAYMENT_DECLINED, mapped?.code)
    }

    @Test
    fun `403 maps to permission denied`() {
        assertEquals(PayabliErrorCode.PERMISSION_DENIED, map(403)?.code)
    }

    @Test
    fun `410 maps to session burned`() {
        // Mapped to the specification; no endpoint has been observed producing a 410.
        assertEquals(PayabliErrorCode.SESSION_BURNED, map(410)?.code)
    }

    @Test
    fun `5xx maps to a server error and keeps the status`() {
        listOf(500, 502, 503, 599).forEach { status ->
            val mapped = map(status)
            assertTrue("status $status", mapped is PayabliServerException)
            assertEquals(PayabliErrorCode.SERVER_ERROR, mapped?.code)
            assertEquals(status, (mapped as PayabliServerException).httpStatus)
        }
    }

    @Test
    fun `an unmapped non-2xx falls through to unknown, naming the status`() {
        listOf(404, 409, 418, 451).forEach { status ->
            val mapped = map(status)
            assertTrue("status $status", mapped is PayabliGenericException)
            assertEquals(PayabliErrorCode.UNKNOWN, mapped?.code)
            assertEquals("HTTP $status", mapped?.reason)
        }
    }

    @Test
    fun `a 400 problem document populates every field it carries`() {
        val body =
            """
            {"type":"https://payabli.com/errors/validation","title":"One or more fields are invalid",
             "status":400,"detail":"See errors","instance":"/api/v2/MoneyIn/getpaid","code":"E1001",
             "errors":{"paymentMethod.cardExp":[{"message":"Expired","suggestion":"Use a future date"}]}}
            """.trimIndent()

        val mapped = map(400, body) as PayabliValidationException

        assertEquals("One or more fields are invalid", mapped.reason)
        assertEquals("See errors", mapped.detail)
        assertEquals("https://payabli.com/errors/validation", mapped.type)
        assertEquals("/api/v2/MoneyIn/getpaid", mapped.instance)
        assertEquals("E1001", mapped.rawCode)
        assertEquals(400, mapped.httpStatus)
        assertEquals(
            "Expired",
            mapped.fieldErrors
                .getValue("paymentMethod.cardExp")
                .single()
                .message,
        )
        assertEquals(
            "Use a future date",
            mapped.fieldErrors
                .getValue("paymentMethod.cardExp")
                .single()
                .suggestion,
        )
    }

    @Test
    fun `a body that will not decode costs fields, never the classification`() {
        // A proxy's HTML error page must not flip the caller's `when (code)` branch.
        val mapped = map(400, "<html><body>502 Bad Gateway</body></html>") as PayabliValidationException
        assertEquals(PayabliErrorCode.VALIDATION_ERROR, mapped.code)
        assertEquals(PayabliValidationException.DEFAULT_REASON, mapped.reason)
        assertTrue(mapped.fieldErrors.isEmpty())
        assertNull(mapped.rawCode)
    }

    @Test
    fun `an unexpected errors shape costs only the field list`() {
        // A stock ASP.NET validation filter emits Map<String, List<String>>, not objects.
        val body = """{"title":"Invalid","errors":{"amount":["must be positive"]}}"""
        val mapped = map(400, body) as PayabliValidationException
        assertEquals("Invalid", mapped.reason)
        assertTrue(mapped.fieldErrors.isEmpty())
    }

    @Test
    fun `a 402 body populates the decline fields`() {
        val body =
            """{"code":"D0329","reason":"Insufficient funds","explanation":"Card has no funds","action":"r"}"""
        val mapped = map(402, body) as PayabliDeclineException
        assertEquals("D0329", mapped.rawCode)
        assertEquals("Insufficient funds", mapped.reason)
        assertEquals("Card has no funds", mapped.explanation)
        assertEquals("Card has no funds", mapped.detail)
        assertEquals("r", mapped.action)
    }

    @Test
    fun `a bodyless 402 is still classified as a decline`() {
        val mapped = map(402) as PayabliDeclineException
        assertEquals(PayabliErrorCode.PAYMENT_DECLINED, mapped.code)
        assertNull(mapped.rawCode)
        assertEquals(PayabliDeclineException.DEFAULT_REASON, mapped.reason)
    }

    @Test
    fun `a 500 problem document populates the server error`() {
        val body = """{"title":"Internal error","detail":"Trace 9","code":"E5000"}"""
        val mapped = map(500, body) as PayabliServerException
        assertEquals("Internal error", mapped.reason)
        assertEquals("Trace 9", mapped.detail)
        assertEquals("E5000", mapped.rawCode)
    }

    @Test
    fun `the override wins for a non-2xx`() {
        val substitute = PayabliGenericException(PayabliErrorCode.MISSING_TOKEN, "device pending activation")
        val mapped = PayabliHttpErrors.from(response(403)) { if (it == 403) substitute else null }
        assertEquals(substitute, mapped)
    }

    @Test
    fun `the override falls through when it returns null`() {
        val mapped = PayabliHttpErrors.from(response(403)) { null }
        assertEquals(PayabliErrorCode.PERMISSION_DENIED, mapped?.code)
    }

    @Test
    fun `the override is not consulted for a 2xx, so it cannot invent a failure`() {
        var consulted = false
        val mapped =
            PayabliHttpErrors.from(response(200)) {
                consulted = true
                PayabliGenericException(PayabliErrorCode.UNKNOWN, "should never happen")
            }
        assertNull(mapped)
        assertFalse(consulted)
    }

    @Test
    fun `the exception message is the code, never the server prose`() {
        // A crash reporter or printStackTrace must not carry text that may echo request data.
        val body = """{"title":"Card number 9999999999999999 is invalid"}"""
        val mapped = map(400, body)
        assertEquals(PayabliErrorCode.VALIDATION_ERROR.wireName, mapped?.message)
        assertFalse(mapped.toString().contains("9999999999999999"))
    }
}
