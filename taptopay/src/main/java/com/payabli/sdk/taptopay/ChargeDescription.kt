package com.payabli.sdk.taptopay

import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.network.sendableAmountOrNull
import java.math.BigDecimal

/**
 * The sale a held key was sent for, read as the service charges it, so a declared retry goes out under that
 * key only when it describes the same sale. Kept in memory beside the key, and never rendered.
 */
internal class ChargeDescription private constructor(
    private val amount: BigDecimal,
    private val serviceFee: BigDecimal,
    private val currency: String?,
    private val invoiceNumber: String?,
) {
    /**
     * Whether [retry] describes this sale. An invoice number counts only when this sale set one.
     *
     * @param retry the description of the declared retry.
     * @return true when the service would charge [retry] as this sale.
     */
    fun matches(retry: ChargeDescription): Boolean =
        amount.compareTo(retry.amount) == 0 &&
            serviceFee.compareTo(retry.serviceFee) == 0 &&
            currency == retry.currency &&
            (invoiceNumber == null || invoiceNumber == retry.invoiceNumber)

    /** Nothing about the sale: the amounts and the invoice are transaction data. */
    override fun toString(): String = "ChargeDescription"

    companion object {
        /**
         * The sale [details] and [invoice] describe.
         *
         * @param details the amounts the charge carries.
         * @param invoice the invoice the charge names.
         * @return the description to hold beside the key, or to compare against the held one.
         */
        fun of(
            details: TapToPayPaymentDetails,
            invoice: TapToPayInvoiceData,
        ): ChargeDescription =
            ChargeDescription(
                amount = details.amount.asCharged(),
                serviceFee = details.serviceFee.asCharged(),
                currency =
                    details.currency
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.uppercase(),
                invoiceNumber = invoice.invoiceNumber?.trim()?.takeIf { it.isNotEmpty() },
            )

        /** The amount at the scale it is written, or as given when it cannot be sent at all. */
        private fun BigDecimal.asCharged(): BigDecimal = sendableAmountOrNull() ?: this
    }
}
