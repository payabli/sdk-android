package com.payabli.sdk.payin.client

import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.model.PayInCustomerData

/**
 * What the payer typed that is not part of the instrument.
 *
 * The form collects a customer, and a description for the method it stores, alongside the card or account
 * details. None of it belongs to `PayInInstrument`, so without this they are typed and then dropped: the QA
 * paypoint answers a capture carrying no customer with `400 Error in customer data`.
 *
 * A configured customer and a typed one meet where the body is written, and the typed value wins for the field
 * it names, because a payer editing the box is the later of the two.
 *
 * **A blank box leaves a configured value standing rather than deleting it.** Of the six fields here, four are
 * ones the form refuses to submit empty, so this can only arise for the customer number and the method
 * description. A caller configuring a customer number has named who the payment belongs to, and an empty
 * optional box is not an instruction to detach it from them.
 */
internal class PayInEnteredDetails(
    val firstName: String? = null,
    val lastName: String? = null,
    val customerNumber: String? = null,
    val billingEmail: String? = null,
    val billingZip: String? = null,
    val methodDescription: String? = null,
) {
    internal companion object {
        /** For a call that reads no form, which is a capture of a transaction authorized earlier. */
        val NONE = PayInEnteredDetails()

        /** The six fields a form can collect that are not the instrument, blank read as absent. */
        fun of(values: PayInFormValues): PayInEnteredDetails =
            PayInEnteredDetails(
                firstName = values.entered(PayInField.FirstName),
                lastName = values.entered(PayInField.LastName),
                customerNumber = values.entered(PayInField.CustomerNumber),
                billingEmail = values.entered(PayInField.BillingEmail),
                billingZip = values.entered(PayInField.BillingPostalCode),
                methodDescription = values.entered(PayInField.MethodDescription),
            )

        private fun PayInFormValues.entered(field: PayInField): String? = this[field].trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * The configured customer with the typed fields over it, or null when neither side named anything.
 *
 * Null rather than an empty object: the service reads a present `customerData` as a customer to act on, and an
 * empty one is a different request from no customer at all.
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
