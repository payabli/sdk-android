package com.payabli.sdk.payin.form

import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.util.extensions.sendableOrNull
import java.math.BigDecimal

/** Why submit refuses a payment's amounts. */
internal enum class PayInAmountRefusal {
    TotalOutOfRange,
    TotalNotMoreThanZero,
    ServiceFeeOutOfRange,
    ServiceFeeNegative,
    SurchargeOutOfRange,
}

/**
 * Why submit refuses these amounts, or null when it accepts them. Submit and the summary both read this, so the
 * summary is empty for exactly the payments submit refuses on their amounts.
 *
 * Each amount is judged at the scale it is sent at: `0.001` is more than zero and reaches the wire as `0.00`.
 */
internal fun PayInPaymentDetails.amountRefusal(): PayInAmountRefusal? {
    // Range before rounding: `setScale` raises ArithmeticException at both extremes of the exponent, measured at
    // 1E+2147483647 and 1E-2147483647, and a merely large exponent would expand into a body megabytes long.
    val total = totalAmount.sendableOrNull() ?: return PayInAmountRefusal.TotalOutOfRange
    if (total <= BigDecimal.ZERO) return PayInAmountRefusal.TotalNotMoreThanZero
    val fee =
        serviceFee?.let { it.sendableOrNull() ?: return PayInAmountRefusal.ServiceFeeOutOfRange }
    if (fee != null && fee < BigDecimal.ZERO) return PayInAmountRefusal.ServiceFeeNegative
    if (surchargeFee != null && surchargeFee.sendableOrNull() == null) return PayInAmountRefusal.SurchargeOutOfRange
    return null
}
