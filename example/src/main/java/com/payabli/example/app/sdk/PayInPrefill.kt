package com.payabli.example.app.sdk

import com.payabli.example.app.demo.qa.QaIdentity
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInMethodType

/**
 * Test values for the payment form, so a QA run is one tap instead of eight fields.
 *
 * Handed to the form as `initialValues`, which is the SDK's own way in: nothing here reaches around the form to
 * write its state.
 *
 * Test card numbers and a published test routing number, so there is nothing here to keep out of the
 * repository.
 *
 * **Every field, the expiry and the account type included.** The values go through the form's own state, so a
 * month and a choice fill like a text box. The expiry is a fixed month, and the form refuses one that has
 * passed, so it is the one value here with a shelf life.
 *
 * The customer is [QaIdentity]'s rather than a constant, because several devices submit these at once and a
 * shared name leaves a dashboard unable to say which one sent what.
 */
object PayInPrefill {
    fun valuesFor(
        method: PayInMethod,
        identity: QaIdentity,
    ): PayInFormSeed =
        PayInFormSeed(
            when (method) {
                PayInMethod.Card -> card(identity)
                PayInMethod.BankAccount -> bankAccount(identity)
            },
        )

    private fun card(identity: QaIdentity) =
        PayInFormValues(
            PayInMethodType.Card,
            mapOf(
                PayInField.CardholderName to identity.holderName,
                PayInField.CardNumber to "4111111111111111",
                PayInField.CardExpiration to "09/30",
                PayInField.CardSecurityCode to "999",
                PayInField.CardPostalCode to "22039",
                PayInField.FirstName to identity.firstName,
                PayInField.LastName to identity.lastName,
                PayInField.CustomerNumber to identity.customerNumber,
                PayInField.BillingEmail to identity.billingEmail,
            ),
        )

    private fun bankAccount(identity: QaIdentity) =
        PayInFormValues(
            PayInMethodType.BankAccount,
            mapOf(
                PayInField.AccountHolder to identity.holderName,
                PayInField.RoutingNumber to "021000021",
                PayInField.AccountNumber to "1111111111",
                // The wire value the choice carries, which is what the field holds and what the request sends.
                PayInField.AccountType to "Checking",
                PayInField.FirstName to identity.firstName,
                PayInField.LastName to identity.lastName,
                PayInField.CustomerNumber to identity.customerNumber,
                PayInField.BillingEmail to identity.billingEmail,
            ),
        )
}
