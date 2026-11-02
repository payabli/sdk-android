package com.payabli.example.app.sdk

import com.payabli.sdk.core.model.PayabliAttempt
import com.payabli.sdk.taptopay.TapToPayCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/** When the Tap to Pay sample declares a retry, so a sale that may have gone through is not taken twice. */
class TapToPayRetryTest {
    private val ten = BigDecimal("10.00")
    private val twenty = BigDecimal("20.00")

    @Test
    fun `the same amount at another scale is the same sale`() {
        assertEquals(PayabliAttempt.Retry, attemptFor(BigDecimal("10.0"), ten))
        // Charged in cents, so an amount that rounds to the open one is the same sale.
        assertEquals(PayabliAttempt.Retry, attemptFor(ten, BigDecimal("10.001")))
        assertEquals(PayabliAttempt.NewPayment, attemptFor(BigDecimal("10.01"), ten))
        assertEquals(PayabliAttempt.NewPayment, attemptFor(twenty, ten))
        assertEquals(PayabliAttempt.NewPayment, attemptFor(ten, null))
    }

    @Test
    fun `an unknown or captured-but-unclosed sale opens its amount`() {
        assertEquals(twenty, openAmountAfter(ten, twenty, TapToPayCapture.UNKNOWN))
        assertEquals(twenty, openAmountAfter(null, twenty, TapToPayCapture.CHARGED))
    }

    @Test
    fun `another sale ending, or a charge that took nothing, keeps the open amount`() {
        assertEquals(ten, openAmountAfter(ten, twenty, capture = null))
        assertEquals(ten, openAmountAfter(ten, twenty, TapToPayCapture.NOT_CHARGED))
        assertEquals(ten, openAmountAfter(ten, ten, TapToPayCapture.NOT_CHARGED))
    }

    @Test
    fun `the open sale succeeding closes it`() {
        assertNull(openAmountAfter(ten, BigDecimal("10.0"), capture = null))
    }
}
