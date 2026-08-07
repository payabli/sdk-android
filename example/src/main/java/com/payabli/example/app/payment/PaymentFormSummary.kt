package com.payabli.example.app.payment

/**
 * The payment form's configuration, read back in words.
 *
 * Every row is **derived** from [PaymentFormConfiguration], never written out by hand. That is the
 * whole point of the section: a screen that transcribed these values would agree with the form on the
 * day it was written and quietly stop agreeing the first time a field moved, and a readout that can be
 * wrong is worse than no readout, because it is believed.
 *
 * Add a field to a section and the field list here grows. Mark a field secret and it appears under
 * masked. Nothing here has to be remembered.
 */
object PaymentFormSummary {
    fun rows(configuration: PaymentFormConfiguration): List<SummaryRow> =
        buildList {
            add(SummaryRow("Allowed methods", configuration.allowedMethods.joinToString(", ") { it.label.lowercase() }))
            add(SummaryRow("Default method", configuration.defaultMethod.label.lowercase()))
            // A method the form will not offer has no fields to describe. Listing them anyway named
            // inputs no payer can reach, which is the drift this section exists to catch.
            configuration.allowedMethods.forEach { method ->
                add(SummaryRow("${method.label} fields", fieldNames(configuration, method)))
            }
            add(SummaryRow("Masked", maskedFields(configuration)))
        }

    /** Only the fields a payer types into; a summary section's figures are not form fields. */
    private fun fieldNames(
        configuration: PaymentFormConfiguration,
        method: PaymentMethodType,
    ): String = fields(configuration, method).joinToString(", ") { it.fieldName }

    private fun fields(
        configuration: PaymentFormConfiguration,
        method: PaymentMethodType,
    ): List<PaymentField> =
        configuration
            .sectionsFor(method)
            .filter { it.style == SectionStyle.Inputs }
            .flatMap { it.fields }

    /** The fields this form renders, not every field the app knows how to render. */
    private fun maskedFields(configuration: PaymentFormConfiguration): String =
        configuration.allowedMethods
            .flatMap { fields(configuration, it) }
            .distinct()
            .filter { it.input == FieldInput.Secret }
            .joinToString(", ") { it.fieldName }
            .ifEmpty { "none" }
}
