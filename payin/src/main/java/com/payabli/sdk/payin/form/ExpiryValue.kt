package com.payabli.sdk.payin.form

import java.util.Calendar
import java.util.Locale

/**
 * A card expiry, as a month and a four-digit year. The payer sees `MM/YY`.
 */
public data class ExpiryValue(
    public val month: Int,
    public val year: Int,
) {
    init {
        // Everything downstream assumes it. A 13 formats to 13/30, which parse refuses, and it makes
        // ExpiryChoices.months return nothing, which coerceMonth reads the first element of.
        require(month in 1..12) { "a month is 1 to 12, not $month" }
        // format writes two digits and parse restores the century, so only this range survives its
        // own round trip: 1999 formats to 99 and reads back as 2099.
        require(year in SUPPORTED_YEARS) { "a year outside $SUPPORTED_YEARS cannot be written as two digits" }
    }

    /**
     * `MM/YY`, in [Locale.ROOT], with [separator] between the two.
     *
     * The default locale renders non-ASCII digits under `ar-EG` and `hi-IN`, which [parse] cannot
     * read back.
     */
    public fun format(separator: String = "/"): String =
        // A "%" in a caller's separator is an argument reference to String.format.
        String.format(Locale.ROOT, "%02d", month) + separator + String.format(Locale.ROOT, "%02d", year % 100)

    /**
     * A card is good through the last day of its expiry month, so that month is not yet expired.
     */
    public fun isExpired(
        currentYear: Int,
        currentMonth: Int,
    ): Boolean = year < currentYear || (year == currentYear && month < currentMonth)

    public companion object {
        /** The century [parse] restores, which is the only one [format] can round-trip. */
        public val SUPPORTED_YEARS: IntRange = 2000..2099

        /**
         * The current month, in the device's time zone. The only clock read in this file.
         *
         * `Calendar` because the module's floor is API 23 with no core library desugaring, so
         * `LocalDate` is not on the device.
         */
        public fun today(): ExpiryValue {
            val now = Calendar.getInstance()
            return ExpiryValue(
                month = now.get(Calendar.MONTH) + 1,
                year = now.get(Calendar.YEAR),
            )
        }

        /**
         * Parses month, any run of non-digits, then a two-digit year in this century.
         *
         * Any separator [PayInFormatting] can produce reads back, so validation needs no
         * configuration.
         */
        public fun parse(text: String): ExpiryValue? {
            val digits = Regex("^\\s*(\\d{1,2})\\D+(\\d{2})\\s*$").find(text) ?: return null
            val month = digits.groupValues[1].toInt()
            val shortYear = digits.groupValues[2].toInt()
            if (month !in 1..12) return null
            return ExpiryValue(month, 2000 + shortYear)
        }
    }
}
