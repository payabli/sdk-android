package com.payabli.sdk.payin.payment

import com.payabli.sdk.payin.client.trimOrNull
import com.payabli.sdk.payin.model.PayInPaymentMethod
import com.payabli.sdk.payin.model.PayInStoredMethodType
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.payin.util.extensions.sendableOrNull
import java.math.BigDecimal

/**
 * The payment a held key was sent for, read as the service charges it, so a declared retry goes out under
 * that key only when it describes the same payment. Kept in memory beside the key, and never rendered.
 */
internal class PayInPaymentDescription private constructor(
    private val total: BigDecimal,
    private val serviceFee: BigDecimal,
    private val surcharge: BigDecimal,
    private val currency: String?,
    private val rail: Rail,
    private val orderId: String?,
) {
    /** The rail the money moves on. A stored method's identifier is not part of it. */
    enum class Rail { CARD, BANK, CHECK, CASH }

    /**
     * Whether [retry] describes this payment. An order ID counts only when this payment set one.
     *
     * @param retry the description of the declared retry.
     * @return true when the service would charge [retry] as this payment.
     */
    fun matches(retry: PayInPaymentDescription): Boolean =
        total.compareTo(retry.total) == 0 &&
            serviceFee.compareTo(retry.serviceFee) == 0 &&
            surcharge.compareTo(retry.surcharge) == 0 &&
            currency == retry.currency &&
            rail == retry.rail &&
            (orderId == null || orderId == retry.orderId)

    /** Nothing about the payment: the amounts and the order are transaction data. */
    override fun toString(): String = "PayInPaymentDescription"

    companion object {
        /**
         * The payment [method] and [options] describe.
         *
         * @param method the instrument being charged.
         * @param options the amounts and order the request carries.
         * @return the description to hold beside the key, or to compare against the held one.
         */
        fun of(
            method: PayInPaymentMethod,
            options: PayInTransactionOptions,
        ): PayInPaymentDescription =
            PayInPaymentDescription(
                total = options.paymentDetails.totalAmount.asCharged(),
                // The service charges an absent fee or surcharge as nothing.
                serviceFee = (options.paymentDetails.serviceFee ?: BigDecimal.ZERO).asCharged(),
                surcharge = (options.paymentDetails.surchargeFee ?: BigDecimal.ZERO).asCharged(),
                currency =
                    options.paymentDetails.currency
                        ?.trimOrNull()
                        ?.uppercase(),
                rail = method.rail(),
                orderId = options.orderId?.trimOrNull(),
            )

        /** The amount at the scale it is written, or as given when it cannot be sent at all. */
        private fun BigDecimal.asCharged(): BigDecimal = sendableOrNull() ?: this

        private fun PayInPaymentMethod.rail(): Rail =
            when (this) {
                is PayInPaymentMethod.Card, is PayInPaymentMethod.CloudDevice -> Rail.CARD
                is PayInPaymentMethod.BankAccount -> Rail.BANK
                is PayInPaymentMethod.Stored ->
                    when (method) {
                        PayInStoredMethodType.Card -> Rail.CARD
                        PayInStoredMethodType.BankAccount -> Rail.BANK
                    }
                is PayInPaymentMethod.Check -> Rail.CHECK
                PayInPaymentMethod.Cash -> Rail.CASH
            }
    }
}
