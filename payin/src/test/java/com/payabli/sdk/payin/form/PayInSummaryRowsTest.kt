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
    fun `amount is the total less the service fee, fee and surcharge are their own figures`() {
        assertEquals(BigDecimal("12.24"), PayInSummaryRows.rowAmount(PayInField.Amount, details))
        assertEquals(BigDecimal("0.10"), PayInSummaryRows.rowAmount(PayInField.ServiceFee, details))
        assertEquals(BigDecimal("0.31"), PayInSummaryRows.rowAmount(PayInField.SurchargeFee, details))
    }

    @Test
    fun `total is the total amount plus the surcharge`() {
        assertEquals(BigDecimal("12.65"), PayInSummaryRows.totalRowAmount(details))
    }

    @Test
    fun `the amount row is read beside a surcharge alone`() {
        val surcharged = PayInPaymentDetails(BigDecimal("12.34"), surchargeFee = BigDecimal("0.31"))

        assertEquals(BigDecimal("12.34"), PayInSummaryRows.rowAmount(PayInField.Amount, surcharged))
    }

    @Test
    fun `the amount row is null with nothing beside it, and total still reads`() {
        val bare = PayInPaymentDetails(BigDecimal("12.34"))

        assertNull(PayInSummaryRows.rowAmount(PayInField.Amount, bare))
        assertNull(PayInSummaryRows.rowAmount(PayInField.ServiceFee, bare))
        assertNull(PayInSummaryRows.rowAmount(PayInField.SurchargeFee, bare))
        assertEquals(BigDecimal("12.34"), PayInSummaryRows.totalRowAmount(bare))
    }

    @Test
    fun `a zero figure is null, including an amount the fee consumes`() {
        val zeroFee = PayInPaymentDetails(BigDecimal("12.34"), serviceFee = BigDecimal.ZERO)
        val allFee = PayInPaymentDetails(BigDecimal("0.10"), serviceFee = BigDecimal("0.10"))

        assertNull(PayInSummaryRows.rowAmount(PayInField.ServiceFee, zeroFee))
        assertNull(PayInSummaryRows.rowAmount(PayInField.Amount, allFee))
    }

    @Test
    fun `a total of zero is null`() {
        val cancelled = PayInPaymentDetails(BigDecimal("0.31"), surchargeFee = BigDecimal("-0.31"))

        assertNull(PayInSummaryRows.totalRowAmount(cancelled))
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
