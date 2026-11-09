package com.payabli.sdk.payin.client

import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.model.PayInCustomerData

/**
 * The customer the payer typed, which is not part of the instrument and would otherwise be dropped.
 *
 * Merged over the configured customer where the body is written, and **a blank box does not delete a configured
 * value**.
 */
internal class PayInEnteredDetails(
    val firstName: String? = null,
    val lastName: String? = null,
    val customerNumber: String? = null,
    val billingEmail: String? = null,
    val billingZip: String? = null,
) {
    internal companion object {
        /** For a call that reads no form, which is a capture of a transaction authorized earlier. */
        val NONE = PayInEnteredDetails()

        /**
         * The five fields a form can collect that are not the instrument, blank read as absent.
         *
         * @param values what the payer entered, and the instrument they entered it for.
         * @return the entered details, blank fields absent.
         */
        fun of(values: PayInFormValues): PayInEnteredDetails =
            PayInEnteredDetails(
                firstName = values.entered(PayInField.FirstName),
                lastName = values.entered(PayInField.LastName),
                customerNumber = values.entered(PayInField.CustomerNumber),
                billingEmail = values.entered(PayInField.BillingEmail),
                billingZip = values.entered(PayInField.BillingPostalCode),
            )

        private fun PayInFormValues.entered(field: PayInField): String? = this[field].trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * The configured customer with the typed fields over it, or null when neither side named anything.
 * Null rather than an empty object: a present `customerData` is a customer to act on, and an empty
 * one is a different request from no customer at all.
 *
 * @param entered what the payer typed, over the configured customer.
 * @return the merged customer body, or null when neither side named anything.
 */
internal fun PayInCustomerData?.toBody(entered: PayInEnteredDetails): CustomerDataBody? {
    val configured = this?.toBody() ?: CustomerDataBody()
    return configured
        .copy(
            firstName = entered.firstName ?: configured.firstName,
            lastName = entered.lastName ?: configured.lastName,
            customerNumber = entered.customerNumber ?: configured.customerNumber,
            billingEmail = entered.billingEmail ?: configured.billingEmail,
            billingZip = entered.billingZip ?: configured.billingZip,
        ).takeIf { it != CustomerDataBody() }
}
