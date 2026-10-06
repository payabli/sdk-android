package com.payabli.sdk.payin.form

import android.content.Context
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.util.extensions.formatAmount
import com.payabli.sdk.payin.util.extensions.sendableOrNull
import java.math.BigDecimal
import java.util.Locale

/**
 * Each summary row as the form draws it, for a host that draws its own: the label, the figure, and the
 * figure as text. A null figure means the row is not drawn.
 */
public object PayInSummaryRows {
    /** The caller's label for [field] in [labels], or the resource. */
    public fun labelText(
        field: PayInField,
        labels: PayInFormLabels,
        context: Context,
    ): String = labels.labelFor(field) ?: context.getString(field.labelResource)

    /** The caller's Total label in [labels], or the resource. */
    public fun totalLabelText(
        labels: PayInFormLabels,
        context: Context,
    ): String = labels.totalOrNull() ?: context.getString(R.string.payabli_payin_summary_total)

    /**
     * The figure on [field]'s row. Amount is the total less the service fee, read only beside a fee or a
     * surcharge, whatever [PayInFormConfiguration.showsBaseAmount] is set to.
     */
    public fun rowAmount(
        field: PayInField,
        details: PayInPaymentDetails?,
    ): BigDecimal? {
        if (details == null) return null
        val fee = details.shownAmount(PayInField.ServiceFee)
        val surcharge = details.shownAmount(PayInField.SurchargeFee)
        return when (field) {
            PayInField.Amount ->
                details
                    .shownAmount(PayInField.Amount)
                    ?.takeIf { fee != null || surcharge != null }
                    ?.subtract(fee ?: BigDecimal.ZERO)
                    ?.takeIf { it.signum() != 0 }
            PayInField.ServiceFee -> fee
            PayInField.SurchargeFee -> surcharge
            else -> null
        }
    }

    /** The figure on the Total row: the total amount plus any surcharge, which is what is charged. */
    public fun totalRowAmount(details: PayInPaymentDetails?): BigDecimal? {
        if (details == null) return null
        val charge = details.shownAmount(PayInField.Amount) ?: return null
        val surcharge = details.shownAmount(PayInField.SurchargeFee) ?: BigDecimal.ZERO
        return charge.add(surcharge).takeIf { it.signum() != 0 }
    }

    /** [amount] as the form writes it: the default locale's separators, [currency]'s symbol, two places. */
    public fun formattedAmount(
        amount: BigDecimal,
        currency: String?,
    ): String = formatAmount(amount, currency, Locale.getDefault())
}

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
    return amount?.sendableOrNull()?.takeIf { it.signum() != 0 }
}
