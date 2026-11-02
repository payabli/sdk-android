package com.payabli.sdk.core.network

import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.leavesOutcomeUnknown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every error mapped from a response carries that response's status, and the code its body names. */
class PayabliHttpErrorsStatusTest {
    private fun map(
        statusCode: Int,
        body: String = "",
    ): PayabliException =
        checkNotNull(
            PayabliHttpErrors.from(PayabliResponse(statusCode, emptyMap(), body.toByteArray(Charsets.UTF_8))),
        )

    @Test
    fun `an unclassified status carries the status and the body's code`() {
        val mapped = map(418, """{"code":"E9999"}""")

        assertEquals(418, mapped.httpStatus)
        assertEquals("E9999", mapped.rawCode)
        assertEquals(PayabliErrorType.UNKNOWN, mapped.type)
    }

    @Test
    fun `an unclassified status whose body will not decode keeps its status and its classification`() {
        val mapped = map(418, "<html><body>I'm a teapot</body></html>")

        assertEquals(418, mapped.httpStatus)
        assertNull(mapped.rawCode)
        assertEquals(PayabliErrorType.UNKNOWN, mapped.type)
    }

    @Test
    fun `the status comes from the response, not from the body`() {
        val mapped = map(418, """{"status":200,"code":"E9999"}""")

        assertEquals(418, mapped.httpStatus)
    }

    @Test
    fun `an unclassified status leaves the outcome unknown`() {
        assertTrue(map(418).type.leavesOutcomeUnknown)
    }

    @Test
    fun `every mapped status carries its status`() {
        listOf(400, 401, 402, 403, 409, 410, 429, 500, 503).forEach { status ->
            assertEquals("status $status", status, map(status).httpStatus)
        }
    }

    @Test
    fun `the generic arms carry the body's code`() {
        listOf(401, 403, 410, 429).forEach { status ->
            assertEquals("status $status", "E1234", map(status, """{"code":"E1234"}""").rawCode)
        }
    }

    @Test
    fun `a decline carries its status and its code`() {
        val mapped = map(402, """{"code":"D1002","reason":"Declined"}""")

        assertEquals(402, mapped.httpStatus)
        assertEquals("D1002", mapped.rawCode)
    }

    @Test
    fun `a conflict's plain-text body yields no code`() {
        val mapped = map(409, "Request with idempotency key abc-123 is already being processed")

        assertEquals(409, mapped.httpStatus)
        assertNull(mapped.rawCode)
    }

    @Test
    fun `a response with no status line carries no status`() {
        val mapped = map(-1)

        assertNull(mapped.httpStatus)
        assertEquals(PayabliErrorType.NETWORK_ERROR, mapped.type)
    }
}
