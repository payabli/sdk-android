package com.payabli.sdk.payin.form

/**
 * The scheme a card number belongs to, from its leading digits.
 *
 * Read as the payer types, and drawn in the number field as the scheme's mark. It is not sent: the service
 * reads the number and decides for itself.
 */
public enum class CardBrand {
    Visa,
    Mastercard,
    AmericanExpress,
    Discover,
    DinersClub,
    Jcb,
    UnionPay,
    Unknown,
    ;

    public companion object {
        /**
         * The brand of a whole number or a prefix.
         *
         * [Unknown] covers both too few digits to tell and no matching range.
         *
         * Issuer identifiers: Visa 4; Mastercard 51-55 and 2221-2720; American Express 34 and 37;
         * Discover 6011, 622126-622925, 644-649 and 65; UnionPay 62; Diners Club 300-305, 3095, 36, 38
         * and 39; JCB 3528-3589.
         *
         * Order matters where one range sits inside another: Discover's 622126-622925 is inside UnionPay's
         * 62, and Diners Club's 3095 is inside JCB's leading 3.
         */
        public fun of(digits: String): CardBrand {
            // ASCII, as PayInFieldRules filters to. Char.isDigit keeps Arabic-Indic digits, which
            // parse to a number here and would brand a value the rules refuse.
            val number = digits.filter { it in '0'..'9' }
            if (number.isEmpty()) return Unknown

            val two = number.prefix(2)
            val three = number.prefix(3)
            val four = number.prefix(4)

            return when {
                number.startsWith("4") -> Visa
                two in 51..55 -> Mastercard
                four in 2221..2720 -> Mastercard
                two == 34 || two == 37 -> AmericanExpress
                four == 6011 -> Discover
                number.prefix(6) in 622126..622925 -> Discover
                three in 644..649 -> Discover
                two == 65 -> Discover
                two == 62 -> UnionPay
                four == 3095 -> DinersClub
                three in 300..305 -> DinersClub
                two == 36 || two == 38 || two == 39 -> DinersClub
                four in 3528..3589 -> Jcb
                else -> Unknown
            }
        }

        /** The first [length] digits as a number, or -1 when there are not that many yet. */
        private fun String.prefix(length: Int): Int = if (this.length < length) -1 else take(length).toInt()
    }
}
