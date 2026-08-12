package com.payabli.sdk.payin.form

/**
 * The card scheme a number belongs to, read from the digits the payer has typed so far.
 *
 * Drawn in the number field as the scheme's own mark, so a payer can see the form recognized their card. It is
 * not sent: the service reads the number and decides for itself.
 *
 * The ranges are the schemes' published issuer-identification prefixes. A number matching none is [Unknown],
 * which is also what a number too short to tell reads as.
 */
internal enum class PayInCardBrand(
    /** What the field shows where the scheme has no artwork here. A brand name, so it is not translated. */
    val display: String,
) {
    Visa("VISA"),
    Mastercard("Mastercard"),
    AmericanExpress("Amex"),
    Discover("Discover"),
    DinersClub("Diners Club"),
    Jcb("JCB"),
    UnionPay("UnionPay"),
    Unknown(""),
    ;

    internal companion object {
        /**
         * The scheme [number]'s digits name, or [Unknown].
         *
         * Anything that is not a digit is dropped first, so a grouped number reads the same as a bare one.
         * Ordered so a longer prefix is tested before a shorter one it sits inside: `65` is Discover and `62`
         * is UnionPay, and Diners' `3095` would otherwise be read as JCB's `30`.
         */
        fun of(number: String): PayInCardBrand {
            val digits = number.filter { it.isDigit() }
            if (digits.length < 2) return if (digits == "4") Visa else Unknown

            val two = digits.take(2).toInt()
            val three = digits.take(3).toIntOrNull()
            val four = digits.take(4).toIntOrNull()

            return when {
                digits.startsWith("4") -> Visa
                two == 34 || two == 37 -> AmericanExpress
                two in 51..55 -> Mastercard
                four != null && four in 2221..2720 -> Mastercard
                four == 6011 || two == 65 -> Discover
                three != null && three in 644..649 -> Discover
                four == 3095 -> DinersClub
                three != null && three in 300..305 -> DinersClub
                two == 36 || two == 38 || two == 39 -> DinersClub
                four != null && four in 3528..3589 -> Jcb
                two == 62 -> UnionPay
                else -> Unknown
            }
        }
    }
}
