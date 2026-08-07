package com.payabli.example.app.payment

import java.util.Locale

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
     * Locale.US, and not the device's.
     *
     * These are US dollar amounts from a US API. The default locale would render 1.00 as "1,00" on a
     * device set to most of Europe, which reads as a different number. A value that will not parse is
     * shown as it arrived.
     */
    internal fun formatAmount(raw: String?): String {
        if (raw.isNullOrBlank()) return MISSING
        val amount = raw.toDoubleOrNull() ?: return raw
        return String.format(Locale.US, "$ %.2f", amount)
    }

    private fun String?.orMissing(): String = if (isNullOrBlank()) MISSING else this
}
