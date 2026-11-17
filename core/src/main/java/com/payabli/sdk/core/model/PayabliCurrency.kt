package com.payabli.sdk.core.model

/**
 * The currency a payment is made in. A currency this SDK does not support has no member, so it cannot be
 * passed.
 *
 * @property code the ISO 4217 code sent on the wire.
 */
public enum class PayabliCurrency(
    public val code: String,
) {
    USD("USD"),
}
