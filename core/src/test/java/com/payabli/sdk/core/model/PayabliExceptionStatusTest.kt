package com.payabli.sdk.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A caller holding only a [PayabliException] reads the same status and code the subtype carries. */
class PayabliExceptionStatusTest {
    @Test
    fun `a validation failure reports its status and code through the base type`() {
        val error: PayabliException = PayabliValidationException(httpStatus = 400, rawCode = "E1001")

        assertEquals(400, error.httpStatus)
        assertEquals("E1001", error.rawCode)
    }

    @Test
    fun `a decline reports its code through the base type`() {
        val error: PayabliException = PayabliDeclineException(rawCode = "D1002")

        assertEquals("D1002", error.rawCode)
    }

    @Test
    fun `a server failure reports its status and code through the base type`() {
        val error: PayabliException = PayabliServerException(httpStatus = 503, rawCode = "E5003")

        assertEquals(503, error.httpStatus)
        assertEquals("E5003", error.rawCode)
    }

    @Test
    fun `a failure built without a response reports neither`() {
        val error: PayabliException = PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "offline")

        assertNull(error.httpStatus)
        assertNull(error.rawCode)
    }
}
