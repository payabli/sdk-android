package com.payabli.example.app.payment

import com.payabli.sdk.payin.form.PayInFieldInput
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInMethodType

/**
 * The payment form's configuration, read back in words.
 *
 * Every row is **derived** from the configuration this app hands the SDK, never written out by hand.
 * A screen that transcribed these values would agree with the form on the day it was written and
 * quietly stop agreeing the first time a field moved, and a readout that can be wrong is worse than
 * no readout, because it is believed.
 *
 * It reads the SDK's own type, so it is also a check on that type: a configuration a reader cannot
 * make sense of here is one an integrator cannot make sense of either.
 */
object PaymentFormSummary {
    fun rows(configuration: PayInFormConfiguration): List<SummaryRow> =
        buildList {
            add(SummaryRow("Allowed methods", configuration.methodsOffered.joinToString(", ") { it.label() }))
            add(SummaryRow("Default method", configuration.startingMethod.label()))
            // A method the form will not offer has no fields to describe. Listing them anyway named
            // inputs no payer can reach, which is the drift this section exists to catch.
            configuration.methodsOffered.forEach { method ->
                add(
                    SummaryRow(
                        "${method.label().replaceFirstChar { it.uppercase() }} fields",
                        fieldNames(configuration, method),
                    ),
                )
            }
            add(SummaryRow("Masked", maskedFields(configuration)))
        }

    private fun fieldNames(
        configuration: PayInFormConfiguration,
        method: PayInMethodType,
    ): String = configuration.inputFieldsFor(method).joinToString(", ") { it.fieldName }

    /** The fields this form renders, not every field the SDK knows how to render. */
    private fun maskedFields(configuration: PayInFormConfiguration): String =
        configuration.methodsOffered
            .flatMap { configuration.inputFieldsFor(it) }
            .distinct()
            .filter { it.input == PayInFieldInput.Secret && configuration.masks(it) }
            .joinToString(", ") { it.fieldName }
            .ifEmpty { "none" }

    /** The SDK names these for a payer, in resources. This readout is for a developer. */
    private fun PayInMethodType.label(): String =
        when (this) {
            PayInMethodType.Card -> "card"
            PayInMethodType.BankAccount -> "bank account"
        }
}
