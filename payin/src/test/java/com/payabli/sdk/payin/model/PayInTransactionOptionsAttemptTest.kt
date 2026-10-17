package com.payabli.sdk.payin.model

import com.payabli.sdk.core.model.PayabliAttempt
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/** A transaction is a new payment unless the host declares it a retry, and names a payment only when given one. */
class PayInTransactionOptionsAttemptTest {
    @Test
    fun `options that declare nothing are a new payment`() {
        val options = PayInTransactionOptions(paymentDetails = PayInPaymentDetails(BigDecimal("10.00")))

        assertEquals(PayabliAttempt.NewPayment, options.attempt)
    }

    @Test
    fun `options keep a declared retry`() {
        val options =
            PayInTransactionOptions(
                paymentDetails = PayInPaymentDetails(BigDecimal("10.00")),
                attempt = PayabliAttempt.Retry,
            )

        assertEquals(PayabliAttempt.Retry, options.attempt)
    }

    @Test
    fun `options name no payment unless the host gives a reference`() {
        val unnamed = PayInTransactionOptions(paymentDetails = PayInPaymentDetails(BigDecimal("10.00")))
        val named =
            PayInTransactionOptions(
                paymentDetails = PayInPaymentDetails(BigDecimal("10.00")),
                paymentReference = "order-7",
            )

        assertNull(unnamed.paymentReference)
        assertEquals("order-7", named.paymentReference)
    }

    @Test
    fun `an unknown outcome carries the reference it is given`() {
        val cause = PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "dropped")

        assertNull(PayInException.Unsettled(cause).paymentReference)
        assertEquals("order-7", PayInException.Unsettled(cause, paymentReference = "order-7").paymentReference)
    }
}
