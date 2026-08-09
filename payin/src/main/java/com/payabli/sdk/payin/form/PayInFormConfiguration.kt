package com.payabli.sdk.payin.form

import androidx.compose.runtime.Immutable

/** Whether a section takes input or reads values back. */
public enum class PayInSectionStyle {
    Inputs,

    /** Values the caller fixed, shown and not typed into. */
    Summary,
}

/** Where a field's label sits. */
public enum class PayInLabelLayout {
    /** Above the field, as its own line. */
    External,

    /** Inside the field, as Material's floating label. */
    Placeholder,
}

/**
 * One group of fields, with a heading.
 *
 * @param title null takes the section's default from string resources.
 */
@Immutable
public data class PayInFormSection(
    val fields: List<PayInField>,
    val title: String? = null,
    val style: PayInSectionStyle = PayInSectionStyle.Inputs,
)

/** The same section over a list nobody else holds a reference to. */
internal fun PayInFormSection.copySnapshot(): PayInFormSection = copy(fields = fields.toList())

/**
 * How a value is written on screen.
 *
 * @param groupsCardNumber card numbers display in fours. The value behind the field stays digits.
 * @param expirySeparator what sits between month and year in the expiry field.
 * @param masksAccountNumber a bank account number is obscured as it is typed, with a reveal control.
 */
@Immutable
public data class PayInFormatting(
    val groupsCardNumber: Boolean = true,
    val expirySeparator: String = "/",
    val masksAccountNumber: Boolean = true,
) {
    init {
        require(expirySeparator.isNotEmpty()) { "an expiry separator of nothing runs the month into the year" }
    }
}

/**
 * What the form collects and how it is arranged. Colours, type and spacing are [PayInFormStyle]'s.
 *
 * Two inputs are corrected on construction: an empty [allowedMethods] becomes [defaultMethod], and a
 * [defaultMethod] outside the allowed set becomes the first allowed one.
 *
 * Every collection here is copied when this is built, and the form reads the copies. That is what
 * `@Immutable` promises Compose, and holding a caller's list on its own does not keep it.
 */
@Immutable
public data class PayInFormConfiguration(
    val allowedMethods: List<PayInMethodType> = listOf(PayInMethodType.Card, PayInMethodType.BankAccount),
    val defaultMethod: PayInMethodType = PayInMethodType.Card,
    val cardSections: List<PayInFormSection> = defaultCardSections(),
    val bankSections: List<PayInFormSection> = defaultBankSections(),
    val requiredFields: Set<PayInField> = emptySet(),
    val labelLayout: PayInLabelLayout = PayInLabelLayout.External,
    val hiddenFieldLabels: Set<PayInField> = emptySet(),
    val formatting: PayInFormatting = PayInFormatting(),
    /** Values for a [PayInSectionStyle.Summary] section, already formatted by the caller. */
    val summaryValues: Map<PayInField, String> = emptyMap(),
) {
    private val methods: List<PayInMethodType> =
        allowedMethods.distinct().ifEmpty { listOf(defaultMethod) }
    private val card: List<PayInFormSection> = cardSections.map { it.copySnapshot() }
    private val bank: List<PayInFormSection> = bankSections.map { it.copySnapshot() }
    private val required: Set<PayInField> = requiredFields.toSet()
    private val hiddenLabels: Set<PayInField> = hiddenFieldLabels.toSet()
    private val summary: Map<PayInField, String> = summaryValues.toMap()

    /** The allowed methods, with duplicates dropped and never empty. */
    public val methodsOffered: List<PayInMethodType> get() = methods

    /** The starting method, always one of [methodsOffered]. */
    public val startingMethod: PayInMethodType
        get() = if (defaultMethod in methods) defaultMethod else methods.first()

    /** What the caller fixed for a summary field, or empty when they fixed nothing. */
    public fun summaryValueFor(field: PayInField): String = summary[field].orEmpty()

    /** The sections for one instrument, with any field appearing twice dropped after its first use. */
    public fun sectionsFor(method: PayInMethodType): List<PayInFormSection> {
        val sections = if (method == PayInMethodType.Card) card else bank
        val seen = mutableSetOf<PayInField>()
        return sections
            .map { section -> section.copy(fields = section.fields.filter { seen.add(it) }) }
            .filter { it.fields.isNotEmpty() }
    }

    /** Every field a payer types into for one instrument, in the order they are rendered. */
    public fun inputFieldsFor(method: PayInMethodType): List<PayInField> =
        sectionsFor(method)
            .filter { it.style == PayInSectionStyle.Inputs }
            .flatMap { it.fields }

    /** True when the field must be filled before the form will submit. */
    public fun isRequired(field: PayInField): Boolean = field in required || PayInFieldRules.missing(field, "")

    /** True when this field's label sits on its own line above the box. */
    public fun showsLabelFor(field: PayInField): Boolean =
        labelLayout == PayInLabelLayout.External && field !in hiddenLabels

    /** True when this field's label sits inside the box, as Material's floating label. */
    public fun showsFloatingLabelFor(field: PayInField): Boolean =
        labelLayout == PayInLabelLayout.Placeholder && field !in hiddenLabels

    public companion object {
        /** Card details, as a payer is asked for them. */
        public fun defaultCardSections(): List<PayInFormSection> =
            listOf(
                PayInFormSection(
                    fields =
                        listOf(
                            PayInField.CardholderName,
                            PayInField.CardNumber,
                            PayInField.CardExpiration,
                            PayInField.CardSecurityCode,
                            PayInField.CardPostalCode,
                        ),
                ),
            )

        /** Bank account details. */
        public fun defaultBankSections(): List<PayInFormSection> =
            listOf(
                PayInFormSection(
                    fields =
                        listOf(
                            PayInField.AccountHolder,
                            PayInField.RoutingNumber,
                            PayInField.AccountNumber,
                            PayInField.AccountType,
                        ),
                ),
            )
    }
}
