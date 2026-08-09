package com.payabli.sdk.payin.form

import java.util.Calendar
import java.util.Locale

/**
 * A card expiry, as a month and a four-digit year. The payer sees `MM/YY`.
 */
public data class ExpiryValue(
    val month: Int,
    val year: Int,
) {
    /**
     * `MM/YY`, in [Locale.ROOT], with [separator] between the two.
     *
     * The default locale renders non-ASCII digits under `ar-EG` and `hi-IN`, which [parse] cannot
     * read back.
     */
    public fun format(separator: String = "/"): String =
        // Two formats and a join, because a separator is a caller's string and a "%" in a format
        // string is an argument reference.
        String.format(Locale.ROOT, "%02d", month) + separator + String.format(Locale.ROOT, "%02d", year % 100)

    /**
     * A card is good through the last day of its expiry month, so that month is not yet expired.
     */
    public fun isExpired(
        currentYear: Int,
        currentMonth: Int,
    ): Boolean = year < currentYear || (year == currentYear && month < currentMonth)

    public companion object {
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
         * Parses month, then anything that is not a digit, then a two-digit year in this century.
         *
         * The separator is whatever [PayInFormatting] chose, and this reads a value back without
         * being told which one, so validation stays a pure function of the text.
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
