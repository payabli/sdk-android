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
        public val minimum: Int,
    ) : PayInFieldError

    /** Longer than any card scheme issues. The form's own field truncates before this. */
    public data class LongerThan(
        public val maximum: Int,
    ) : PayInFieldError

    /** Longer than the field accepts, counted in characters. A postal code is not digits. */
    public data class TooManyCharacters(
        public val maximum: Int,
    ) : PayInFieldError

    /** A field with one acceptable length. A routing number is the only one. */
    public data class NotExactly(
        public val length: Int,
    ) : PayInFieldError

    /** A field with a range of acceptable lengths: a security code, an account number. */
    public data class OutsideRange(
        public val minimum: Int,
        public val maximum: Int,
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

    /**
     * The service refused this field.
     *
     * The one case no rule in this file produces: it comes back with a refusal, about a value that has already
     * been sent. Its wording says only that, because the refusal's own reason may echo what the payer typed.
     */
    public data object NotAccepted : PayInFieldError
}

/**
 * The rejection as a fixed word, for the report that counts it.
 *
 * The parameterised cases drop their number, which is the rule's bound rather than anything typed. A
 * report keyed on it would be open-ended where every other property value comes from a fixed set.
 */
internal val PayInFieldError.reason: String
    get() =
        when (this) {
            PayInFieldError.DigitsOnly -> "digitsOnly"
            is PayInFieldError.ShorterThan -> "shorterThan"
            is PayInFieldError.LongerThan -> "longerThan"
            is PayInFieldError.TooManyCharacters -> "tooManyCharacters"
            is PayInFieldError.NotExactly -> "notExactly"
            is PayInFieldError.OutsideRange -> "outsideRange"
            PayInFieldError.CardNumberNotValid -> "cardNumberNotValid"
            PayInFieldError.RoutingNumberNotValid -> "routingNumberNotValid"
            PayInFieldError.EmailNotValid -> "emailNotValid"
            PayInFieldError.ExpiryIncomplete -> "expiryIncomplete"
            PayInFieldError.ExpiryPast -> "expiryPast"
            PayInFieldError.NotAccepted -> "notAccepted"
        }
