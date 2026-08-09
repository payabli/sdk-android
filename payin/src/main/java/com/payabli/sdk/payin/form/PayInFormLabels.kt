package com.payabli.sdk.payin.form

import androidx.compose.runtime.Immutable

/**
 * Wording decided at runtime, for what is not known when resources are written.
 *
 * Anything left null or blank comes from `res/values`, where an integrator can redeclare any
 * `payabli_payin_*` string and a translator can add a `values-xx`.
 *
 * The two maps are copied on construction, so what the form reads cannot change afterwards, which
 * is what `@Immutable` states to Compose.
 */
@Immutable
public data class PayInFormLabels(
    public val title: String? = null,
    public val subtitle: String? = null,
    public val submitButton: String? = null,
    public val fieldLabels: Map<PayInField, String> = emptyMap(),
    public val fieldPlaceholders: Map<PayInField, String> = emptyMap(),
) {
    private val labels: Map<PayInField, String> = fieldLabels.toMap()
    private val placeholders: Map<PayInField, String> = fieldPlaceholders.toMap()

    /** The caller's label for a field, or null to use the resource. */
    public fun labelFor(field: PayInField): String? = labels[field]?.takeIf { it.isNotBlank() }

    /** The caller's placeholder for a field, or null for none. */
    public fun placeholderFor(field: PayInField): String? = placeholders[field]?.takeIf { it.isNotBlank() }

    /** The heading above the form, or null for none. */
    public fun titleOrNull(): String? = title?.takeIf { it.isNotBlank() }

    /** The line under the heading, or null for none. */
    public fun subtitleOrNull(): String? = subtitle?.takeIf { it.isNotBlank() }

    /** The wording on the submit button, or null to use the resource. */
    public fun submitButtonOrNull(): String? = submitButton?.takeIf { it.isNotBlank() }

    /**
     * Compares the copies, which is what [labelFor] and [placeholderFor] read.
     *
     * The generated equality compares the constructor properties, so two instances holding one map
     * that was mutated between them compare equal while their copies differ.
     */
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is PayInFormLabels &&
                    title == other.title &&
                    subtitle == other.subtitle &&
                    submitButton == other.submitButton &&
                    labels == other.labels &&
                    placeholders == other.placeholders
            )

    override fun hashCode(): Int =
        listOf(title, subtitle, submitButton, labels, placeholders)
            .fold(0) { hash, part -> 31 * hash + part.hashCode() }
}
