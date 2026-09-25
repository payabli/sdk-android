package com.payabli.sdk.payin.ui

import com.payabli.sdk.payin.client.atWireScale
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInSectionStyle
import com.payabli.sdk.payin.model.PayInPaymentDetails
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * The figure a summary row shows for [field], or null when the row is not drawn.
 *
 * Read at the scale the amount is sent at, so a row appears exactly when a figure above zero is charged.
 */
internal fun PayInPaymentDetails.shownAmount(field: PayInField): BigDecimal? {
    val amount =
        when (field) {
            PayInField.Amount -> totalAmount
            PayInField.ServiceFee -> serviceFee
            PayInField.SurchargeFee -> surchargeFee
            else -> null
        }
    return amount?.atWireScale()?.takeIf { it > BigDecimal.ZERO }
}

/** A section as it is drawn, with the figures it shows when it is the summary. */
internal class DrawnSection(
    val section: PayInFormSection,
    val amounts: List<Pair<PayInField, BigDecimal>> = emptyList(),
)

/**
 * [sections] with every amount above zero placed in one summary.
 *
 * A host's summary section decides where the figures go and what the section is called, never which figures
 * appear: they are in the order it lists them, then any it left out. With no summary section one is appended
 * after the rest. With nothing above zero to show, no summary is drawn.
 */
internal fun placeAmounts(
    sections: List<PayInFormSection>,
    amounts: PayInPaymentDetails?,
): List<DrawnSection> {
    val inputs = sections.filter { it.style == PayInSectionStyle.Inputs }.map(::DrawnSection)
    if (amounts == null || AMOUNT_FIELDS.none { amounts.shownAmount(it) != null }) return inputs

    val summary =
        sections.firstOrNull { it.style == PayInSectionStyle.Summary }
            ?: PayInFormSection(fields = AMOUNT_FIELDS, style = PayInSectionStyle.Summary)
    val order = (summary.fields.filter { it in AMOUNT_FIELDS } + AMOUNT_FIELDS).distinct()
    val drawn = DrawnSection(summary, order.mapNotNull { field -> amounts.shownAmount(field)?.let { field to it } })

    val at = sections.indexOf(summary)
    return if (at < 0) {
        inputs + drawn
    } else {
        // Where the host put it, among the inputs before and after it.
        val before = sections.take(at).count { it.style == PayInSectionStyle.Inputs }
        inputs.take(before) + drawn + inputs.drop(before)
    }
}

private val AMOUNT_FIELDS = listOf(PayInField.Amount, PayInField.ServiceFee, PayInField.SurchargeFee)

/**
 * [amount] in [locale]'s grouping and decimal separator, with [currency]'s symbol.
 *
 * A currency that is absent or not an ISO 4217 code draws the number with no symbol: the charge is then made
 * in a currency the request does not name, so any symbol here would be a guess.
 */
internal fun formatAmount(
    amount: BigDecimal,
    currency: String?,
    locale: Locale,
): String {
    val named = currency?.let(::currencyOrNull)
    val format =
        if (named == null) {
            NumberFormat.getNumberInstance(locale)
        } else {
            NumberFormat.getCurrencyInstance(locale).apply { this.currency = named }
        }
    // The two places the wire carries, whatever the currency's own convention, so the row is the figure sent.
    format.minimumFractionDigits = WIRE_FRACTION_DIGITS
    format.maximumFractionDigits = WIRE_FRACTION_DIGITS
    format.roundingMode = RoundingMode.HALF_UP
    return format.format(amount.atWireScale())
}

private fun currencyOrNull(code: String): Currency? =
    try {
        Currency.getInstance(code.trim().uppercase(Locale.ROOT))
    } catch (_: IllegalArgumentException) {
        null
    }

private const val WIRE_FRACTION_DIGITS = 2
