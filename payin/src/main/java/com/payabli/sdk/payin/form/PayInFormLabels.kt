package com.payabli.sdk.payin.form

import androidx.compose.runtime.Immutable
import java.util.Collections

/**
 * Wording decided at runtime, for what is not known when resources are written.
 *
 * Anything left null or blank comes from `res/values`, where an integrator can redeclare any
 * `payabli_payin_*` string and a translator can add a `values-xx`.
 *
 * The two maps are copied at construction, so what the form reads cannot change afterwards, which is
 * what `@Immutable` states to Compose. The parameters are not `val`, as `PayabliResponse` in
 * `:core` writes it: a property beside the copy would publish the uncopied original, and a
 * `data class`'s generated `copy()` would rebuild from it.
 */
@Immutable
public class PayInFormLabels(
    public val title: String? = null,
    public val subtitle: String? = null,
    public val submitButton: String? = null,
    fieldLabels: Map<PayInField, String> = emptyMap(),
    fieldPlaceholders: Map<PayInField, String> = emptyMap(),
) {
    public val fieldLabels: Map<PayInField, String> = Collections.unmodifiableMap(fieldLabels.toMap())
    public val fieldPlaceholders: Map<PayInField, String> =
        Collections.unmodifiableMap(fieldPlaceholders.toMap())

    /** The caller's label for a field, or null to use the resource. */
    public fun labelFor(field: PayInField): String? = fieldLabels[field]?.takeIf { it.isNotBlank() }

    /** The caller's placeholder for a field, or null for none. */
    public fun placeholderFor(field: PayInField): String? = fieldPlaceholders[field]?.takeIf { it.isNotBlank() }

    /** The heading above the form, or null for none. */
    public fun titleOrNull(): String? = title?.takeIf { it.isNotBlank() }

    /** The line under the heading, or null for none. */
    public fun subtitleOrNull(): String? = subtitle?.takeIf { it.isNotBlank() }

    /** The wording on the submit button, or null to use the resource. */
    public fun submitButtonOrNull(): String? = submitButton?.takeIf { it.isNotBlank() }

    /** As a `data class` would, over the copies rather than over what was handed in. */
    public fun copy(
        title: String? = this.title,
        subtitle: String? = this.subtitle,
        submitButton: String? = this.submitButton,
        fieldLabels: Map<PayInField, String> = this.fieldLabels,
        fieldPlaceholders: Map<PayInField, String> = this.fieldPlaceholders,
    ): PayInFormLabels = PayInFormLabels(title, subtitle, submitButton, fieldLabels, fieldPlaceholders)

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is PayInFormLabels &&
                    title == other.title &&
                    subtitle == other.subtitle &&
                    submitButton == other.submitButton &&
                    fieldLabels == other.fieldLabels &&
                    fieldPlaceholders == other.fieldPlaceholders
            )

    override fun hashCode(): Int =
        listOf(title, subtitle, submitButton, fieldLabels, fieldPlaceholders)
            .fold(0) { hash, part -> 31 * hash + part.hashCode() }

    override fun toString(): String =
        "PayInFormLabels(title=$title, subtitle=$subtitle, submitButton=$submitButton, " +
            "fieldLabels=$fieldLabels, fieldPlaceholders=$fieldPlaceholders)"
}
