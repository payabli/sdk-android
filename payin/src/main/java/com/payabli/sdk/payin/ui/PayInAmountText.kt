package com.payabli.sdk.payin.ui

import com.payabli.sdk.payin.client.PayInValidation
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
 * Read at the scale the amount is sent at, so a row appears exactly when a figure other than zero is sent. An
 * amount too large or too precise to send draws none, since submitting it is refused.
 */
internal fun PayInPaymentDetails.shownAmount(field: PayInField): BigDecimal? {
    val amount =
        when (field) {
            PayInField.Amount -> totalAmount
            PayInField.ServiceFee -> serviceFee
            PayInField.SurchargeFee -> surchargeFee
            else -> null
        }
    val sendable = amount?.let { with(PayInValidation) { it.sendableOrNull() } }
    return sendable?.takeIf { it.signum() != 0 }
}

/** A section as it is drawn, with the figures it shows when it is the summary, and its [total] row if any. */
internal class DrawnSection(
    val section: PayInFormSection,
    val amounts: List<Pair<PayInField, BigDecimal>> = emptyList(),
    val total: BigDecimal? = null,
)

/**
 * [sections] with every amount other than zero placed in one summary.
 *
 * A host's summary section decides where the figures go and what the section is called, never which figures
 * appear: they are in the order it lists them, then any it left out. With no summary section one is appended
 * after the rest. With nothing but zero to show, no summary is drawn.
 *
 * The section's total is `totalAmount` plus the surcharge, which is what the service charges. The Amount row is
 * `totalAmount` less the service fee, drawn only with [showsBaseAmount] and a fee or surcharge beside it.
 */
internal fun placeAmounts(
    sections: List<PayInFormSection>,
    amounts: PayInPaymentDetails?,
    showsBaseAmount: Boolean = true,
): List<DrawnSection> {
    val inputs = sections.filter { it.style == PayInSectionStyle.Inputs }.map(::DrawnSection)
    if (amounts == null || AMOUNT_FIELDS.none { amounts.shownAmount(it) != null }) return inputs

    val summary =
        sections.firstOrNull { it.style == PayInSectionStyle.Summary }
            ?: PayInFormSection(fields = AMOUNT_FIELDS, style = PayInSectionStyle.Summary)
    val order = (summary.fields.filter { it in AMOUNT_FIELDS } + AMOUNT_FIELDS).distinct()
    val shown = order.associateWith { amounts.shownAmount(it) }
    val fee = shown[PayInField.ServiceFee]
    val surcharge = shown[PayInField.SurchargeFee]
    val charge = shown[PayInField.Amount]
    val total = charge?.add(surcharge ?: BigDecimal.ZERO)?.takeIf { it.signum() != 0 }
    val baseAmount =
        charge
            ?.takeIf { showsBaseAmount && (fee != null || surcharge != null) }
            ?.subtract(fee ?: BigDecimal.ZERO)
            ?.takeIf { it.signum() != 0 }
    val figures = shown + (PayInField.Amount to baseAmount)
    val drawn = DrawnSection(summary, order.mapNotNull { field -> figures[field]?.let { field to it } }, total)

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
