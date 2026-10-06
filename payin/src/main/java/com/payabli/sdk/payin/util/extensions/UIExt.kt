package com.payabli.sdk.payin.util.extensions

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

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
    format.minimumFractionDigits = AMOUNT_SCALE
    format.maximumFractionDigits = AMOUNT_SCALE
    format.roundingMode = RoundingMode.HALF_UP
    return format.format(amount.atWireScale())
}

private fun currencyOrNull(code: String): Currency? =
    try {
        Currency.getInstance(code.trim().uppercase(Locale.ROOT))
    } catch (_: IllegalArgumentException) {
        null
    }
