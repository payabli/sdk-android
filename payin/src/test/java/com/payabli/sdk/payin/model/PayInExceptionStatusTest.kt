package com.payabli.sdk.payin.model

import com.payabli.sdk.core.model.PayabliException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A refusal and a service error report the status and code of the answer they were read from. */
class PayInExceptionStatusTest {
    private fun failure(
        code: String?,
        httpStatus: Int?,
    ) = PayInFailure(code = code, reason = null, explanation = null, action = null, httpStatus = httpStatus)

    @Test
    fun `a refusal reports its status and code through the base type`() {
        val error: PayabliException = PayInException.Refused(failure("D1002", 200))

        assertEquals(200, error.httpStatus)
        assertEquals("D1002", error.rawCode)
    }

    @Test
    fun `a service error reports its status and code through the base type`() {
        val error: PayabliException = PayInException.ServiceError(failure("E4001", 500))

        assertEquals(500, error.httpStatus)
        assertEquals("E4001", error.rawCode)
    }

    @Test
    fun `a refusal read without a status reports none`() {
        val error: PayabliException = PayInException.Refused(failure(null, null))

        assertNull(error.httpStatus)
        assertNull(error.rawCode)
    }
}
