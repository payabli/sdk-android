package com.payabli.sdk.payin.form

/**
 * The scheme a card number belongs to, from its leading digits.
 */
public enum class CardBrand {
    Visa,
    Mastercard,
    AmericanExpress,
    Discover,
    Unknown,
    ;

    public companion object {
        /**
         * The brand of a whole number or a prefix.
         *
         * [Unknown] covers both too few digits to tell and no matching range.
         *
         * Issuer identifiers: Visa 4; Mastercard 51-55 and 2221-2720; American Express 34 and 37;
         * Discover 6011, 644-649 and 65.
         */
        public fun of(digits: String): CardBrand {
            val number = digits.filter { it.isDigit() }
            if (number.isEmpty()) return Unknown

            val two = number.prefix(2)
            val four = number.prefix(4)

            return when {
                number.startsWith("4") -> Visa
                two in 51..55 -> Mastercard
                four in 2221..2720 -> Mastercard
                two == 34 || two == 37 -> AmericanExpress
                four == 6011 -> Discover
                number.prefix(3) in 644..649 -> Discover
                two == 65 -> Discover
                else -> Unknown
            }
        }

        /** The first [length] digits as a number, or -1 when there are not that many yet. */
        private fun String.prefix(length: Int): Int = if (this.length < length) -1 else take(length).toInt()
    }
}
