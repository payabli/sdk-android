package com.payabli.sdk.payin.model

import com.payabli.sdk.core.model.PayabliErrorType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The public constructors a Java caller compiled against, which a Kotlin call site cannot see go missing. */
class PayInExceptionConstructorTest {
    @Test
    fun `an unreadable answer still has the no-argument constructor`() {
        val built = PayInException.Undecodable::class.java.getConstructor().newInstance()

        assertEquals(PayabliErrorType.DECODING_ERROR, built.type)
    }

    @Test
    fun `an unreadable answer still has the constructor taking a cause`() {
        val built = PayInException.Undecodable::class.java.getConstructor(Throwable::class.java).newInstance(null)

        assertEquals(PayabliErrorType.DECODING_ERROR, built.type)
    }

    @Test
    fun `a service error still has the constructor taking the failure, typed as a server error`() {
        val failure = PayInFailure(code = "E4001", reason = null, explanation = null, action = null, httpStatus = 500)

        val built =
            PayInException.ServiceError::class.java
                .getConstructor(
                    PayInFailure::class.java,
                ).newInstance(failure)

        assertEquals(PayabliErrorType.SERVER_ERROR, built.type)
    }
}
