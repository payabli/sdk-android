package com.payabli.sdk.core.network

import com.payabli.sdk.core.model.PayabliDeclineException
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.model.PayabliServerException
import com.payabli.sdk.core.model.PayabliValidationException
import kotlinx.serialization.json.Json
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
    fun `the string form the platform sends keeps the field name and the message`() {
        // Captured verbatim from a live 400: an `errors` map of field name to a list of plain strings, which
        // is the shape observed, and the map key is the one thing a form needs to mark the offending field.
        val body =
            """
            {"errors":{"Entry":["The Entry field is required."]},"status":400,
             "title":"One or more validation errors occurred.",
             "traceId":"00-0866011d65bd828be7d3f8f78e4adb09-c49ae1fadac8da6e-01",
             "type":"https://tools.ietf.org/html/rfc9110#section-15.5.1"}
            """.trimIndent()

        val mapped = map(400, body) as PayabliValidationException

        assertEquals("One or more validation errors occurred.", mapped.reason)
        assertEquals(setOf("Entry"), mapped.fieldErrors.keys)
        val entry = mapped.fieldErrors.getValue("Entry").single()
        assertEquals("The Entry field is required.", entry.message)
        assertNull(entry.suggestion)
    }

    @Test
    fun `a missing required property reaches the caller naming itself`() {
        // Also captured verbatim: POST /attest with the `platform` key absent. `$` is the body as a whole
        // rather than a field in it, and the string under it is the most useful diagnostic in the family.
        val body =
            """
            {"errors":{
               "$":["JSON deserialization for type 'AttestRequest' was missing required properties including: 'platform'."],
               "request":["The request field is required."]},
             "status":400,"title":"One or more validation errors occurred.",
             "type":"https://tools.ietf.org/html/rfc9110#section-15.5.1"}
            """.trimIndent()

        val mapped = map(400, body) as PayabliValidationException

        assertEquals(setOf("$", "request"), mapped.fieldErrors.keys)
        assertTrue(
            mapped.fieldErrors
                .getValue("$")
                .single()
                .message
                .contains("'platform'"),
        )
        assertEquals(
            "The request field is required.",
            mapped.fieldErrors
                .getValue("request")
                .single()
                .message,
        )
    }

    @Test
    fun `the two forms mix, within one body and within one field`() {
        val body =
            """
            {"title":"Invalid","errors":{
               "amount":["must be positive"],
               "paymentMethod.cardExp":[{"message":"Expired","suggestion":"Use a future date"},"check the year"]}}
            """.trimIndent()

        val mapped = map(400, body) as PayabliValidationException

        assertEquals(
            "must be positive",
            mapped.fieldErrors
                .getValue("amount")
                .single()
                .message,
        )
        val expiry = mapped.fieldErrors.getValue("paymentMethod.cardExp")
        assertEquals(listOf("Expired", "check the year"), expiry.map { it.message })
        assertEquals(listOf("Use a future date", null), expiry.map { it.suggestion })
    }

    @Test
    fun `an errors shape that is neither costs only the field list`() {
        // A number, a null, a nested array, a bare value where an array belongs, and an `errors` that is an
        // array. Each fails the map decode, and the fields that come from the problem-details decode survive
        // it.
        listOf(
            """{"title":"Invalid","errors":{"amount":[42]}}""",
            """{"title":"Invalid","errors":{"amount":[null]}}""",
            """{"title":"Invalid","errors":{"amount":[["must be positive"]]}}""",
            """{"title":"Invalid","errors":{"amount":"must be positive"}}""",
            """{"title":"Invalid","errors":["must be positive"]}""",
        ).forEach { body ->
            val mapped = map(400, body) as PayabliValidationException
            assertEquals(body, PayabliErrorCode.VALIDATION_ERROR, mapped.code)
            assertEquals(body, "Invalid", mapped.reason)
            assertTrue(body, mapped.fieldErrors.isEmpty())
        }
    }

    @Test
    fun `a number is not a message, whatever the codec's strictness`() {
        // A lenient codec, because `PayabliJson` is strict and its strictness alone would keep 42 out. The
        // string check in `FieldError` is what makes the outcome independent of that setting: without it, a
        // lenient codec reads 42 as the message text.
        val lenient =
            Json {
                isLenient = true
                ignoreUnknownKeys = true
                explicitNulls = false
            }

        val decoded =
            runCatching {
                lenient.decodeFromString(
                    PayabliHttpErrors.ErrorsMap.serializer(),
                    """{"errors":{"amount":[42]}}""",
                )
            }

        assertTrue(decoded.isFailure)
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
