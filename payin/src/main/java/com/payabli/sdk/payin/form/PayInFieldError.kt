package com.payabli.sdk.payin.form

/**
 * What is wrong with a field.
 *
 * A value, not a message. The wording lives in `strings.xml` and the Compose layer turns these into
 * text. Each case carries the numbers its message needs.
 */
public sealed interface PayInFieldError {
    /** Something that is not a digit reached a digits-only field. */
    public data object DigitsOnly : PayInFieldError

    /** Shorter than any card scheme issues. */
    public data class ShorterThan(
        val minimum: Int,
    ) : PayInFieldError

    /** A field with one acceptable length. A routing number is the only one. */
    public data class NotExactly(
        val length: Int,
    ) : PayInFieldError

    /** A field with a range of acceptable lengths: a security code, an account number. */
    public data class OutsideRange(
        val minimum: Int,
        val maximum: Int,
    ) : PayInFieldError

    /** Well formed and fails the Luhn check digit. */
    public data object CardNumberNotValid : PayInFieldError

    /** Nine digits and fails the ABA checksum. */
    public data object RoutingNumberNotValid : PayInFieldError

    /** Not the shape of an address. Whether one exists is the mail server's answer. */
    public data object EmailNotValid : PayInFieldError

    /** Nothing chosen, or not `MM/YY`. */
    public data object ExpiryIncomplete : PayInFieldError

    /** A month that has already passed. */
    public data object ExpiryPast : PayInFieldError
}
