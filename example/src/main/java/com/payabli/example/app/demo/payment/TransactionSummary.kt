package com.payabli.example.app.demo.payment

import java.math.RoundingMode

/** One line of the transaction readout. */
data class SummaryRow(
    val label: String,
    val value: String,
)

/**
 * The transaction, as an ordered list of rows.
 *
 * Every row is present whether or not it has a value. A reader comparing two payments needs them to
 * line up, and a row that disappears when empty makes an absent value look like a row that was never
 * meant to be there.
 */
object TransactionSummary {
    private const val MISSING = "—"

    fun rows(result: PaymentResult): List<SummaryRow> {
        val transaction = result.transaction
        return listOf(
            SummaryRow("Code", result.code.ifBlank { MISSING }),
            SummaryRow("Reason", result.reason.orMissing()),
            SummaryRow("Explanation", result.explanation.orMissing()),
            SummaryRow("Action", result.action.orMissing()),
            SummaryRow("Payment transaction", transaction?.paymentTransactionId.orMissing()),
            SummaryRow("Gateway transaction", transaction?.gatewayTransactionId.orMissing()),
            SummaryRow("Order", transaction?.orderId.orMissing()),
            SummaryRow("Method", transaction?.method.orMissing()),
            SummaryRow("Operation", transaction?.operation.orMissing()),
            SummaryRow("Status", transaction?.status.orMissing()),
            SummaryRow("Total", formatAmount(transaction?.totalAmount)),
            SummaryRow("Fee", formatAmount(transaction?.feeAmount)),
            SummaryRow("Source", transaction?.source.orMissing()),
        )
    }

    /**
     * Always a dot for the decimal point, whatever the device's locale is.
     *
     * These are US dollar amounts from a US API, and a locale-aware formatter renders 1.00 as "1,00"
     * across most of Europe, which reads as a different number. `toPlainString` is locale-independent
     * by contract, so no locale is passed and none can be picked up. A value that will not parse is
     * shown as it arrived.
     */
    internal fun formatAmount(raw: String?): String {
        if (raw.isNullOrBlank()) return MISSING
        // BigDecimal, because binary floating point cannot hold most decimal fractions exactly and
        // a wide enough value loses digits before it is ever displayed. This is a financial readout
        // shown beside the raw response, so the two disagreeing is the failure to avoid.
        val amount = raw.trim().toBigDecimalOrNull() ?: return raw
        return "$ " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    private fun String?.orMissing(): String = if (isNullOrBlank()) MISSING else this
}
