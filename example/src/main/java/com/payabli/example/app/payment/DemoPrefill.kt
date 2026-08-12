package com.payabli.example.app.payment

import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInMethodType

/**
 * Test values for the payment form, so a QA run is one tap instead of eight fields.
 *
 * Handed to the form as `initialValues`, which is the SDK's own way in: nothing here reaches around the form to
 * write its state. iOS does the same thing from `DebugPrefill.json`.
 *
 * Test card numbers and a published test routing number, so there is nothing here to keep out of the
 * repository.
 *
 * **Every field, the expiry and the account type included.** The values go through the form's own state, so a
 * month and a choice fill like a text box. The expiry is a fixed month, and the form refuses one that has
 * passed, so it is the one value here with a shelf life.
 */
object DemoPrefill {
    fun valuesFor(method: PayInMethodType): PayInFormValues =
        when (method) {
            PayInMethodType.Card -> card
            PayInMethodType.BankAccount -> bankAccount
        }

    private val card =
        PayInFormValues(
            PayInMethodType.Card,
            mapOf(
                PayInField.CardholderName to "QA Tester",
                PayInField.CardNumber to "4111111111111111",
                PayInField.CardExpiration to "09/30",
                PayInField.CardSecurityCode to "999",
                PayInField.CardPostalCode to "22039",
                PayInField.FirstName to "QA",
                PayInField.LastName to "Tester",
                PayInField.CustomerNumber to "qa-tester-android",
                PayInField.BillingEmail to "qa@example.com",
            ),
        )

    private val bankAccount =
        PayInFormValues(
            PayInMethodType.BankAccount,
            mapOf(
                PayInField.AccountHolder to "QA Tester",
                PayInField.RoutingNumber to "021000021",
                PayInField.AccountNumber to "1111111111",
                // The wire value the choice carries, which is what the field holds and what the request sends.
                PayInField.AccountType to "Checking",
                PayInField.FirstName to "QA",
                PayInField.LastName to "Tester",
                PayInField.CustomerNumber to "qa-tester-android",
                PayInField.BillingEmail to "qa@example.com",
            ),
        )
}
