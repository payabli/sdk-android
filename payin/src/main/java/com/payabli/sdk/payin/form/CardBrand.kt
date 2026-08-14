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
         * One pair of these can match the same number, so its order decides the answer: Discover's
         * 622126-622925 is inside UnionPay's 62. Every other pair is disjoint, checked over every six-digit
         * prefix, so nothing else here depends on where it sits.
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
                two == 62 -> if (number.mayReachDiscoversRange()) Unknown else UnionPay
                four == 3095 -> DinersClub
                three in 300..305 -> DinersClub
                two == 36 || two == 38 || two == 39 -> DinersClub
                four in 3528..3589 -> Jcb
                else -> Unknown
            }
        }

        /** The first [length] digits as a number, or -1 when there are not that many yet. */
        private fun String.prefix(length: Int): Int = if (this.length < length) -1 else take(length).toInt()

        /**
         * Whether these digits could still turn out to be Discover's `622126-622925`.
         *
         * The check above needs six digits, and answering UnionPay before then puts the wrong mark beside a
         * Discover number for four keystrokes. Compared at the length typed so far, so a 62 number that has
         * already left the range is marked at once, without waiting for a sixth digit it does not need.
         */
        private fun String.mayReachDiscoversRange(): Boolean {
            if (length >= DISCOVER_RANGE_DIGITS) return false
            return take(length).toInt() in
                DISCOVER_RANGE_LOW.take(length).toInt()..DISCOVER_RANGE_HIGH.take(length).toInt()
        }

        private const val DISCOVER_RANGE_DIGITS = 6
        private const val DISCOVER_RANGE_LOW = "622126"
        private const val DISCOVER_RANGE_HIGH = "622925"
    }
}

/**
 * The scheme's name, as the field shows it and as a screen reader announces the mark.
 *
 * Brand names, so they are not translated resources. The enum spells three of them as one word, and a screen
 * reader reads this value aloud.
 */
internal fun CardBrand.schemeName(): String =
    when (this) {
        CardBrand.AmericanExpress -> "American Express"
        CardBrand.DinersClub -> "Diners Club"
        CardBrand.Jcb -> "JCB"
        CardBrand.UnionPay -> "UnionPay"
        CardBrand.Visa, CardBrand.Mastercard, CardBrand.Discover, CardBrand.Unknown -> name
    }
