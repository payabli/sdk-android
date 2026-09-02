package com.payabli.example.app.sdk

import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.sdk.payin.form.PayInField

/**
 * Test values for the payment form, so a demo run is one tap instead of eight fields.
 *
 * In the debug source set with [DebugPrefill], which is where a tool for a demo run belongs and is what keeps
 * a test card out of anything else.
 *
 * **One set for both instruments.** A card form and a bank form ask for different fields, and what names the
 * payer is the same either way, so [DebugPrefill] fills the boxes that are on screen and neither it nor this
 * has to know which tab the form is on.
 *
 * Test card numbers and a published test routing number, so there is nothing here to keep out of the
 * repository.
 *
 * **The expiry and the account type are not here.** Both are chosen from a picker rather than typed, and a
 * picker takes no text: [DebugPrefill] says why. A demo run picks those two by hand.
 *
 * The customer is [SampleIdentity]'s, so several devices submitting at once produce rows a dashboard can
 * attribute to the device that sent them.
 */
object PayInPrefill {
    fun valuesFor(identity: SampleIdentity): Map<PayInField, String> =
        mapOf(
            PayInField.CardholderName to identity.holderName,
            PayInField.CardNumber to "4111111111111111",
            PayInField.CardSecurityCode to "999",
            PayInField.CardPostalCode to "22039",
            PayInField.AccountHolder to identity.holderName,
            PayInField.RoutingNumber to "121000248",
            PayInField.AccountNumber to "1234567890",
            PayInField.FirstName to identity.firstName,
            PayInField.LastName to identity.lastName,
            PayInField.CustomerNumber to identity.customerNumber,
            PayInField.BillingEmail to identity.billingEmail,
        )
}
