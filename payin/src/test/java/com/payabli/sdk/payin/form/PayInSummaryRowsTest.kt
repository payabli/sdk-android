package com.payabli.sdk.payin.form

import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.util.extensions.formatAmount
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

/** What a host reads for each summary row: the figure, or null where the row is not drawn. */
class PayInSummaryRowsTest {
    private val details =
        PayInPaymentDetails(
            totalAmount = BigDecimal("12.34"),
            serviceFee = BigDecimal("0.10"),
            surchargeFee = BigDecimal("0.31"),
            currency = "USD",
        )

    private lateinit var savedLocale: Locale

    @Before
    fun pinLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `the service fee and the surcharge are the figures supplied`() {
        assertEquals(BigDecimal("0.10"), PayInSummaryRows.rowAmount(PayInField.ServiceFee, details))
        assertEquals(BigDecimal("0.31"), PayInSummaryRows.rowAmount(PayInField.SurchargeFee, details))
    }

    @Test
    fun `total is the total amount supplied`() {
        assertEquals(BigDecimal("12.34"), PayInSummaryRows.totalRowAmount(details))
    }

    @Test
    fun `the amount row has no figure, beside a fee, a surcharge, both or neither`() {
        listOf(
            details,
            PayInPaymentDetails(BigDecimal("12.34"), serviceFee = BigDecimal("0.50")),
            PayInPaymentDetails(BigDecimal("10.30"), surchargeFee = BigDecimal("0.30")),
            PayInPaymentDetails(BigDecimal("12.34")),
        ).forEach { paymentDetails ->
            assertNull("$paymentDetails", PayInSummaryRows.rowAmount(PayInField.Amount, paymentDetails))
        }
    }

    @Test
    fun `a surcharged charge shows the surcharge and the total as supplied`() {
        val surcharged = PayInPaymentDetails(BigDecimal("10.30"), surchargeFee = BigDecimal("0.30"))

        assertNull(PayInSummaryRows.rowAmount(PayInField.ServiceFee, surcharged))
        assertEquals(BigDecimal("0.30"), PayInSummaryRows.rowAmount(PayInField.SurchargeFee, surcharged))
        assertEquals(BigDecimal("10.30"), PayInSummaryRows.totalRowAmount(surcharged))
    }

    @Test
    fun `fees that reach the total still show every figure supplied`() {
        val reaching =
            PayInPaymentDetails(BigDecimal("1.00"), serviceFee = BigDecimal("0.50"), surchargeFee = BigDecimal("0.50"))
        val beyond = PayInPaymentDetails(BigDecimal("0.30"), surchargeFee = BigDecimal("0.50"))

        assertEquals(BigDecimal("0.50"), PayInSummaryRows.rowAmount(PayInField.ServiceFee, reaching))
        assertEquals(BigDecimal("0.50"), PayInSummaryRows.rowAmount(PayInField.SurchargeFee, reaching))
        assertEquals(BigDecimal("1.00"), PayInSummaryRows.totalRowAmount(reaching))
        assertEquals(BigDecimal("0.50"), PayInSummaryRows.rowAmount(PayInField.SurchargeFee, beyond))
        assertEquals(BigDecimal("0.30"), PayInSummaryRows.totalRowAmount(beyond))
    }

    @Test
    fun `a zero fee is null`() {
        val zeroFee = PayInPaymentDetails(BigDecimal("12.34"), serviceFee = BigDecimal.ZERO)

        assertNull(PayInSummaryRows.rowAmount(PayInField.ServiceFee, zeroFee))
    }

    @Test
    fun `figures are read at the scale they are sent`() {
        val precise = PayInPaymentDetails(BigDecimal("12.345"), serviceFee = BigDecimal("0.004"))

        assertNull(PayInSummaryRows.rowAmount(PayInField.ServiceFee, precise))
        assertEquals(BigDecimal("12.35"), PayInSummaryRows.totalRowAmount(precise))
    }

    @Test
    fun `an amount that cannot be sent reads as null`() {
        val unsendable = PayInPaymentDetails(BigDecimal("1E+40"), serviceFee = BigDecimal("0.10"))

        assertNull(PayInSummaryRows.rowAmount(PayInField.Amount, unsendable))
        assertNull(PayInSummaryRows.totalRowAmount(unsendable))
    }

    @Test
    fun `a surcharge that cannot be sent empties every figure`() {
        val unsendable =
            PayInPaymentDetails(
                BigDecimal("12.34"),
                serviceFee = BigDecimal("0.10"),
                surchargeFee = BigDecimal("1E+40"),
            )

        assertNull(PayInSummaryRows.rowAmount(PayInField.Amount, unsendable))
        assertNull(PayInSummaryRows.rowAmount(PayInField.ServiceFee, unsendable))
        assertNull(PayInSummaryRows.rowAmount(PayInField.SurchargeFee, unsendable))
        assertNull(PayInSummaryRows.totalRowAmount(unsendable))
    }

    @Test
    fun `a fee that cannot be sent empties every figure`() {
        val unsendable =
            PayInPaymentDetails(
                BigDecimal("12.34"),
                serviceFee = BigDecimal("1E+40"),
                surchargeFee = BigDecimal("0.31"),
            )

        assertNull(PayInSummaryRows.rowAmount(PayInField.Amount, unsendable))
        assertNull(PayInSummaryRows.rowAmount(PayInField.ServiceFee, unsendable))
        assertNull(PayInSummaryRows.rowAmount(PayInField.SurchargeFee, unsendable))
        assertNull(PayInSummaryRows.totalRowAmount(unsendable))
    }

    @Test
    fun `a total that cannot be sent empties the fee and surcharge figures too`() {
        val unsendable =
            PayInPaymentDetails(BigDecimal("1E+40"), serviceFee = BigDecimal("0.10"), surchargeFee = BigDecimal("0.31"))

        assertNull(PayInSummaryRows.rowAmount(PayInField.ServiceFee, unsendable))
        assertNull(PayInSummaryRows.rowAmount(PayInField.SurchargeFee, unsendable))
    }

    @Test
    fun `a negative service fee empties every figure`() {
        assertNoFigures(PayInPaymentDetails(BigDecimal("12.34"), serviceFee = BigDecimal("-0.5")))
    }

    @Test
    fun `a total that is not more than zero empties every figure`() {
        assertNoFigures(PayInPaymentDetails(BigDecimal("-5"), surchargeFee = BigDecimal("1")))
    }

    @Test
    fun `a total sent as zero empties every figure`() {
        assertNoFigures(PayInPaymentDetails(BigDecimal("0.001"), serviceFee = BigDecimal("0.5")))
    }

    private fun assertNoFigures(refused: PayInPaymentDetails) {
        assertNull(PayInSummaryRows.rowAmount(PayInField.Amount, refused))
        assertNull(PayInSummaryRows.rowAmount(PayInField.ServiceFee, refused))
        assertNull(PayInSummaryRows.rowAmount(PayInField.SurchargeFee, refused))
        assertNull(PayInSummaryRows.totalRowAmount(refused))
    }

    @Test
    fun `a field that is not money has no figure`() {
        assertNull(PayInSummaryRows.rowAmount(PayInField.CardholderName, details))
    }

    @Test
    fun `no details reads as null`() {
        assertNull(PayInSummaryRows.rowAmount(PayInField.Amount, null))
        assertNull(PayInSummaryRows.rowAmount(PayInField.ServiceFee, null))
        assertNull(PayInSummaryRows.totalRowAmount(null))
    }

    @Test
    fun `an amount too extreme to round is written as its digits rather than raising`() {
        // setScale raises ArithmeticException at both extremes of the exponent.
        listOf("1E+2147483647", "1E-2147483647", "1E+1001").forEach { extreme ->
            assertEquals(extreme, PayInSummaryRows.formattedAmount(BigDecimal(extreme), "USD"))
        }
    }

    @Test
    fun `formatting uses the default locale`() {
        assertEquals("1.234,56\u00A0€", PayInSummaryRows.formattedAmount(BigDecimal("1234.56"), "EUR"))
        assertEquals(
            formatAmount(BigDecimal("1234.56"), "USD", Locale.getDefault()),
            PayInSummaryRows.formattedAmount(BigDecimal("1234.56"), "USD"),
        )
    }
}
