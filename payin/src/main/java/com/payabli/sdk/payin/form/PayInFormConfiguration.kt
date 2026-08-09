package com.payabli.sdk.payin.form

import androidx.compose.runtime.Immutable

/** Whether a section takes input or reads values back. */
public enum class PayInSectionStyle {
    Inputs,

    /** Values the caller fixed. A payer reads them; the form has no box for them. */
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
    public val fields: List<PayInField>,
    public val title: String? = null,
    public val style: PayInSectionStyle = PayInSectionStyle.Inputs,
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
    public val groupsCardNumber: Boolean = true,
    public val expirySeparator: String = "/",
    public val masksAccountNumber: Boolean = true,
) {
    init {
        require(expirySeparator.isNotEmpty()) { "an expiry separator of nothing runs the month into the year" }
        require(expirySeparator.none { it in '0'..'9' }) {
            // "1" writes 07130, which ExpiryValue.parse reads as no month and year at all, so the
            // picker's own value fails validation and the form never submits.
            "an expiry separator of digits cannot be told from the month and year around it"
        }
    }
}

/**
 * What the form collects and how it is arranged. Colours, type and spacing are [PayInFormStyle]'s.
 *
 * Two inputs are corrected on construction: an empty [allowedMethods] becomes [defaultMethod], and a
 * [defaultMethod] outside the allowed set becomes the first allowed one.
 *
 * Every collection is copied on construction and the form reads the copies, which is what
 * `@Immutable` states to Compose.
 */
@Immutable
public data class PayInFormConfiguration(
    public val allowedMethods: List<PayInMethodType> = listOf(PayInMethodType.Card, PayInMethodType.BankAccount),
    public val defaultMethod: PayInMethodType = PayInMethodType.Card,
    public val cardSections: List<PayInFormSection> = defaultCardSections(),
    public val bankSections: List<PayInFormSection> = defaultBankSections(),
    public val requiredFields: Set<PayInField> = emptySet(),
    public val labelLayout: PayInLabelLayout = PayInLabelLayout.External,
    public val hiddenFieldLabels: Set<PayInField> = emptySet(),
    public val formatting: PayInFormatting = PayInFormatting(),
    /** Values for a [PayInSectionStyle.Summary] section, already formatted by the caller. */
    public val summaryValues: Map<PayInField, String> = emptyMap(),
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

    /**
     * Compares the copies, which is what every accessor above reads.
     *
     * The generated equality compares the constructor properties, so two configurations holding one
     * list that was mutated between them compare equal while their copies differ. Compose is then
     * entitled to skip a form whose fields have changed.
     */
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is PayInFormConfiguration &&
                    defaultMethod == other.defaultMethod &&
                    labelLayout == other.labelLayout &&
                    formatting == other.formatting &&
                    methods == other.methods &&
                    card == other.card &&
                    bank == other.bank &&
                    required == other.required &&
                    hiddenLabels == other.hiddenLabels &&
                    summary == other.summary
            )

    override fun hashCode(): Int =
        listOf(defaultMethod, labelLayout, formatting, methods, card, bank, required, hiddenLabels, summary)
            .fold(0) { hash, part -> 31 * hash + part.hashCode() }

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
