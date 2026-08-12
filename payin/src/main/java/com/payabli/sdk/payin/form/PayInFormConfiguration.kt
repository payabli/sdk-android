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

    /**
     * Inside the field, as Material's floating label.
     *
     * Material owns its type here, so [PayInFormStyle.label] governs [External] and this one takes
     * the host's typography. See that property for what was measured.
     */
    Placeholder,
}

/**
 * One group of fields, with a heading.
 *
 * [fields] is copied at construction, for the reason given on [PayInFormConfiguration].
 *
 * @param title null takes the section's default from string resources.
 */
@Immutable
public class PayInFormSection(
    fields: List<PayInField>,
    public val title: String? = null,
    public val style: PayInSectionStyle = PayInSectionStyle.Inputs,
) {
    public val fields: List<PayInField> = fields.toList()

    /** As a `data class` would, over the copy rather than over what was handed in. */
    public fun copy(
        fields: List<PayInField> = this.fields,
        title: String? = this.title,
        style: PayInSectionStyle = this.style,
    ): PayInFormSection = PayInFormSection(fields, title, style)

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PayInFormSection && fields == other.fields && title == other.title && style == other.style)

    override fun hashCode(): Int = (31 * (31 * fields.hashCode() + title.hashCode())) + style.hashCode()

    override fun toString(): String = "PayInFormSection(fields=$fields, title=$title, style=$style)"
}

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
 * What the form collects and how it is arranged. Colors, type and spacing are [PayInFormStyle]'s.
 *
 * [allowedMethods] and [defaultMethod] are what a caller passed, unchanged. Two corrections apply to
 * what the form reads instead: [methodsOffered] drops duplicates and is never empty, and
 * [startingMethod] is always one of it. Read those two rather than the raw pair.
 *
 * Every collection is copied at construction, which is what `@Immutable` states to Compose. The
 * parameters are not `val`, as `PayabliResponse` in `:core` writes it: a property beside the copy
 * would publish the uncopied original, and a `data class`'s generated `copy()` would rebuild from
 * it, so a no-argument copy could differ from its source.
 */
@Immutable
public class PayInFormConfiguration(
    allowedMethods: List<PayInMethodType> = listOf(PayInMethodType.Card, PayInMethodType.BankAccount),
    public val defaultMethod: PayInMethodType = PayInMethodType.Card,
    cardSections: List<PayInFormSection> = defaultCardSections(),
    bankSections: List<PayInFormSection> = defaultBankSections(),
    requiredFields: Set<PayInField> = emptySet(),
    public val labelLayout: PayInLabelLayout = PayInLabelLayout.External,
    hiddenFieldLabels: Set<PayInField> = emptySet(),
    public val formatting: PayInFormatting = PayInFormatting(),
    /** Values for a [PayInSectionStyle.Summary] section, already formatted by the caller. */
    summaryValues: Map<PayInField, String> = emptyMap(),
) {
    public val allowedMethods: List<PayInMethodType> = allowedMethods.toList()
    public val cardSections: List<PayInFormSection> = cardSections.toList()
    public val bankSections: List<PayInFormSection> = bankSections.toList()
    public val requiredFields: Set<PayInField> = requiredFields.toSet()
    public val hiddenFieldLabels: Set<PayInField> = hiddenFieldLabels.toSet()
    public val summaryValues: Map<PayInField, String> = summaryValues.toMap()

    private val methods: List<PayInMethodType> =
        this.allowedMethods.distinct().ifEmpty { listOf(defaultMethod) }

    /** The allowed methods, with duplicates dropped and never empty. */
    public val methodsOffered: List<PayInMethodType> get() = methods

    /** The starting method, always one of [methodsOffered]. */
    public val startingMethod: PayInMethodType
        get() = if (defaultMethod in methods) defaultMethod else methods.first()

    /** What the caller fixed for a summary field, or empty when they fixed nothing. */
    public fun summaryValueFor(field: PayInField): String = summaryValues[field].orEmpty()

    /** The sections for one instrument, with any field appearing twice dropped after its first use. */
    public fun sectionsFor(method: PayInMethodType): List<PayInFormSection> {
        val sections = if (method == PayInMethodType.Card) cardSections else bankSections
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
    public fun isRequired(field: PayInField): Boolean = field in requiredFields || PayInFieldRules.missing(field, "")

    /** True when this field's label sits on its own line above the box. */
    public fun showsLabelFor(field: PayInField): Boolean =
        labelLayout == PayInLabelLayout.External && field !in hiddenFieldLabels

    /** True when this field's label sits inside the box, as Material's floating label. */
    public fun showsFloatingLabelFor(field: PayInField): Boolean =
        labelLayout == PayInLabelLayout.Placeholder && field !in hiddenFieldLabels

    /**
     * True when this field is obscured as it is typed.
     *
     * A secret field is, except an account number under [PayInFormatting.masksAccountNumber] off.
     * Public because a host that describes its own form has no other way to know.
     */
    public fun masks(field: PayInField): Boolean =
        field.input == PayInFieldInput.Secret &&
            (field != PayInField.AccountNumber || formatting.masksAccountNumber)

    /**
     * As a `data class` would, over the copies rather than over what was handed in.
     *
     * One parameter per property, which is what makes it a copy. The parameter-count rule is turned
     * off for this file in the root `build.gradle.kts`, which says why.
     */
    public fun copy(
        allowedMethods: List<PayInMethodType> = this.allowedMethods,
        defaultMethod: PayInMethodType = this.defaultMethod,
        cardSections: List<PayInFormSection> = this.cardSections,
        bankSections: List<PayInFormSection> = this.bankSections,
        requiredFields: Set<PayInField> = this.requiredFields,
        labelLayout: PayInLabelLayout = this.labelLayout,
        hiddenFieldLabels: Set<PayInField> = this.hiddenFieldLabels,
        formatting: PayInFormatting = this.formatting,
        summaryValues: Map<PayInField, String> = this.summaryValues,
    ): PayInFormConfiguration =
        PayInFormConfiguration(
            allowedMethods,
            defaultMethod,
            cardSections,
            bankSections,
            requiredFields,
            labelLayout,
            hiddenFieldLabels,
            formatting,
            summaryValues,
        )

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is PayInFormConfiguration &&
                    defaultMethod == other.defaultMethod &&
                    labelLayout == other.labelLayout &&
                    formatting == other.formatting &&
                    allowedMethods == other.allowedMethods &&
                    cardSections == other.cardSections &&
                    bankSections == other.bankSections &&
                    requiredFields == other.requiredFields &&
                    hiddenFieldLabels == other.hiddenFieldLabels &&
                    summaryValues == other.summaryValues
            )

    override fun hashCode(): Int =
        listOf(
            defaultMethod,
            labelLayout,
            formatting,
            allowedMethods,
            cardSections,
            bankSections,
            requiredFields,
            hiddenFieldLabels,
            summaryValues,
        ).fold(0) { hash, part -> 31 * hash + part.hashCode() }

    override fun toString(): String =
        "PayInFormConfiguration(allowedMethods=$allowedMethods, defaultMethod=$defaultMethod, " +
            "cardSections=$cardSections, bankSections=$bankSections, requiredFields=$requiredFields, " +
            "labelLayout=$labelLayout, hiddenFieldLabels=$hiddenFieldLabels, formatting=$formatting, " +
            // Keys, never values. Nothing stops a caller putting a card field in here, and a
            // toString reaches a log line and a crash report without anyone deciding it should.
            "summaryValues=${summaryValues.keys})"

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
