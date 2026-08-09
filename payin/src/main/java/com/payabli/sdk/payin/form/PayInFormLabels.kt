package com.payabli.sdk.payin.form

import androidx.compose.runtime.Immutable

/**
 * Wording decided at runtime, for what is not known when resources are written.
 *
 * Anything left null or blank comes from `res/values`, where an integrator can redeclare any
 * `payabli_payin_*` string and a translator can add a `values-xx`.
 */
@Immutable
public data class PayInFormLabels(
    val title: String? = null,
    val subtitle: String? = null,
    val submitButton: String? = null,
    val fieldLabels: Map<PayInField, String> = emptyMap(),
    val fieldPlaceholders: Map<PayInField, String> = emptyMap(),
) {
    /** The caller's label for a field, or null to use the resource. */
    public fun labelFor(field: PayInField): String? = fieldLabels[field]?.takeIf { it.isNotBlank() }

    /** The caller's placeholder for a field, or null for none. */
    public fun placeholderFor(field: PayInField): String? = fieldPlaceholders[field]?.takeIf { it.isNotBlank() }
}
