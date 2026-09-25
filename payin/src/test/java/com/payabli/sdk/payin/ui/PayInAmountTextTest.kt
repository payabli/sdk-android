package com.payabli.sdk.payin.ui

import com.payabli.sdk.payin.form.CARD_INSTRUMENT_FIELDS
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInSectionStyle
import com.payabli.sdk.payin.model.PayInPaymentDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

/** What a summary row shows, and whether it is drawn at all. */
class PayInAmountTextTest {
    private val details =
        PayInPaymentDetails(
            totalAmount = BigDecimal("12.34"),
            serviceFee = BigDecimal("0.10"),
            surchargeFee = BigDecimal("0.31"),
            currency = "USD",
        )

    @Test
    fun `each amount field reads its own figure`() {
        assertEquals(BigDecimal("12.34"), details.shownAmount(PayInField.Amount))
        assertEquals(BigDecimal("0.10"), details.shownAmount(PayInField.ServiceFee))
        assertEquals(BigDecimal("0.31"), details.shownAmount(PayInField.SurchargeFee))
    }

    @Test
    fun `an absent or zero amount draws no row`() {
        val bare = PayInPaymentDetails(totalAmount = BigDecimal("12.34"), serviceFee = BigDecimal.ZERO)

        assertNull(bare.shownAmount(PayInField.ServiceFee))
        assertNull(bare.shownAmount(PayInField.SurchargeFee))
    }

    @Test
    fun `an amount that is sent as zero draws no row`() {
        // 0.001 is not zero as supplied and reaches the wire as 0.00.
        val tiny = PayInPaymentDetails(totalAmount = BigDecimal("12.34"), serviceFee = BigDecimal("0.001"))

        assertNull(tiny.shownAmount(PayInField.ServiceFee))
    }

    @Test
    fun `an amount too large or too precise to send draws no row rather than failing the form`() {
        listOf("1E+2147483647", "1E-2147483647").forEach { extreme ->
            assertNull(extreme, PayInPaymentDetails(BigDecimal(extreme)).shownAmount(PayInField.Amount))
        }
    }

    @Test
    fun `a negative amount that is sent is drawn as the minus figure it is`() {
        val credit = PayInPaymentDetails(totalAmount = BigDecimal("12.34"), surchargeFee = BigDecimal("-0.31"))

        assertEquals(BigDecimal("-0.31"), credit.shownAmount(PayInField.SurchargeFee))
        assertEquals("-$0.31", formatAmount(BigDecimal("-0.31"), "USD", Locale.US))
    }

    @Test
    fun `a field that is not an amount draws no row`() {
        assertNull(details.shownAmount(PayInField.CardholderName))
    }

    private val card = PayInFormSection(fields = CARD_INSTRUMENT_FIELDS)
    private val customer = PayInFormSection(fields = listOf(PayInField.FirstName))

    private fun summary(vararg fields: PayInField) =
        PayInFormSection(fields = fields.toList(), title = "Order", style = PayInSectionStyle.Summary)

    private fun fieldsOf(drawn: List<DrawnSection>) = drawn.map { section -> section.amounts.map { it.first } }

    @Test
    fun `with no summary section one is appended after the inputs`() {
        val drawn = placeAmounts(listOf(card, customer), details)

        assertEquals(listOf(card, customer), drawn.dropLast(1).map { it.section })
        assertEquals(PayInSectionStyle.Summary, drawn.last().section.style)
        assertEquals(
            listOf(PayInField.Amount, PayInField.ServiceFee, PayInField.SurchargeFee),
            drawn.last().amounts.map { it.first },
        )
    }

    @Test
    fun `a summary that lists only the amount still shows every figure that is not zero`() {
        val drawn = placeAmounts(listOf(card, summary(PayInField.Amount)), details)

        assertEquals(
            listOf(emptyList(), listOf(PayInField.Amount, PayInField.ServiceFee, PayInField.SurchargeFee)),
            fieldsOf(drawn),
        )
    }

    @Test
    fun `the host's summary keeps its place, its title and its order`() {
        val host = summary(PayInField.SurchargeFee, PayInField.Amount)
        val drawn = placeAmounts(listOf(card, host, customer), details)

        assertEquals(listOf(card, host, customer), drawn.map { it.section })
        assertEquals(
            listOf(PayInField.SurchargeFee, PayInField.Amount, PayInField.ServiceFee),
            drawn[1].amounts.map { it.first },
        )
    }

    @Test
    fun `a second summary section is not drawn`() {
        val drawn = placeAmounts(listOf(summary(PayInField.Amount), card, summary(PayInField.ServiceFee)), details)

        assertEquals(1, drawn.count { it.section.style == PayInSectionStyle.Summary })
    }

    @Test
    fun `nothing but zero, or nothing charged, draws no summary`() {
        val zero = PayInPaymentDetails(totalAmount = BigDecimal.ZERO)

        assertEquals(listOf(card), placeAmounts(listOf(card, summary(PayInField.Amount)), zero).map { it.section })
        assertEquals(listOf(card), placeAmounts(listOf(card, summary(PayInField.Amount)), null).map { it.section })
    }

    @Test
    fun `the figure is the one sent, at two places`() {
        assertEquals("$12.35", formatAmount(BigDecimal("12.345"), "USD", Locale.US))
        assertEquals("$1,234.50", formatAmount(BigDecimal("1234.5"), "USD", Locale.US))
    }

    @Test
    fun `the device locale decides the separators and the currency decides the symbol`() {
        assertEquals("1.234,56 €", formatAmount(BigDecimal("1234.56"), "EUR", Locale.GERMANY))
        assertEquals("€1,234.56", formatAmount(BigDecimal("1234.56"), "EUR", Locale.US))
    }

    @Test
    fun `a currency with no minor unit still shows the two places sent`() {
        assertEquals("¥1,234.56", formatAmount(BigDecimal("1234.56"), "JPY", Locale.US))
    }

    @Test
    fun `a currency the request does not name draws the number alone`() {
        assertEquals("1,234.56", formatAmount(BigDecimal("1234.56"), null, Locale.US))
        assertEquals("1.234,56", formatAmount(BigDecimal("1234.56"), null, Locale.GERMANY))
        assertEquals("1,234.56", formatAmount(BigDecimal("1234.56"), "dollars", Locale.US))
    }

    @Test
    fun `a code in lower case or with spaces is still the currency it names`() {
        assertEquals("$12.34", formatAmount(BigDecimal("12.34"), " usd ", Locale.US))
    }
}
