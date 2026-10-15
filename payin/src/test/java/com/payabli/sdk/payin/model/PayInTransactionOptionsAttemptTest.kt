package com.payabli.sdk.payin.model

import com.payabli.sdk.core.model.PayabliAttempt
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/** A transaction is a new payment unless the host declares it a retry. */
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
}
