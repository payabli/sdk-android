package com.payabli.sdk.payin.form

import androidx.compose.runtime.Immutable

/**
 * What the payer entered, and which instrument they entered it for.
 *
 * [values] holds the fields of [method] and nothing else. Switching instrument drops what the other
 * one had, so a card number is not still in here behind a bank form.
 */
@Immutable
public data class PayInFormValues(
    public val method: PayInMethodType,
    public val values: Map<PayInField, String>,
) {
    /** The value for a field, or empty when it was never typed into. */
    public operator fun get(field: PayInField): String = values[field].orEmpty()
}
