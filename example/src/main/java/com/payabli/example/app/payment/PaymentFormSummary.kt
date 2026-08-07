package com.payabli.example.app.payment

/**
 * The payment form's configuration, read back in words.
 *
 * Every row is **derived** from [PaymentFormConfiguration] and [PaymentFieldRules], never written out
 * by hand. That is the whole point of the section: a screen that transcribed these values would agree
 * with the form on the day it was written and quietly stop agreeing the first time a field moved, and
 * a readout that can be wrong is worse than no readout, because it is believed.
 *
 * Add a field to a section and the field list here grows. Mark a field secret and it appears under
 * masked. Nothing here has to be remembered.
 */
object PaymentFormSummary {
    fun rows(configuration: PaymentFormConfiguration): List<SummaryRow> =
        listOf(
            SummaryRow("Allowed methods", configuration.allowedMethods.joinToString(", ") { it.label.lowercase() }),
            SummaryRow("Default method", configuration.defaultMethod.label.lowercase()),
            SummaryRow("Card fields", fieldNames(configuration, PaymentMethodType.Card)),
            SummaryRow("Bank fields", fieldNames(configuration, PaymentMethodType.BankAccount)),
            SummaryRow("Masked", maskedFields()),
        )

    /** Only the fields a payer types into; a summary section's figures are not form fields. */
    private fun fieldNames(
        configuration: PaymentFormConfiguration,
        method: PaymentMethodType,
    ): String =
        configuration
            .sectionsFor(method)
            .filter { it.style == SectionStyle.Inputs }
            .flatMap { it.fields }
            .joinToString(", ") { it.fieldName }

    private fun maskedFields(): String =
        PaymentField.entries
            .filter { it.input == FieldInput.Secret }
            .joinToString(", ") { it.fieldName }
            .ifEmpty { "none" }
}
