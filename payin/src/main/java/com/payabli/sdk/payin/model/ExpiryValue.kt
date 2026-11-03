package com.payabli.sdk.payin.model

import java.util.Calendar
import java.util.Locale

/**
 * A card expiry, as a month and a four-digit year. The payer sees `MM/YY`.
 */
internal data class ExpiryValue(
    val month: Int,
    val year: Int,
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
     * `MM/YY`, in [Locale.ROOT], with [separator] between the two. The default locale renders
     * non-ASCII digits under `ar-EG` and `hi-IN`, which [parse] cannot read back.
     *
     * @param separator what sits between the month and the year.
     * @return the month and the two-digit year, with [separator] between.
     */
    fun format(separator: String = "/"): String = format(month, year, separator)

    /**
     * A card is good through the last day of its expiry month, so that month is not yet expired.
     *
     * @param currentYear the year now.
     * @param currentMonth the month now.
     * @return true when the card's last good day has passed; false otherwise.
     */
    fun isExpired(
        currentYear: Int,
        currentMonth: Int,
    ): Boolean = isExpired(month, year, currentYear, currentMonth)

    companion object {
        /**
         * `MM/YY` for any month and year, in [Locale.ROOT]. Takes values outside the range this type holds,
         * because card data carries its expiry unchecked until a call refuses it.
         *
         * @param month the month, written as two digits.
         * @param year the year; only its last two digits are written.
         * @param separator what sits between the month and the year.
         * @return the month and the two-digit year, with [separator] between.
         */
        fun format(
            month: Int,
            year: Int,
            separator: String = "/",
        ): String =
            // A "%" in a caller's separator is an argument reference to String.format.
            String.format(Locale.ROOT, "%02d", month) + separator + String.format(Locale.ROOT, "%02d", year % 100)

        /**
         * Whether a card expiring in [month] of [year] has passed its last good day. Takes values outside the
         * range this type holds, as [format] does.
         *
         * @param month the month the card expires.
         * @param year the year the card expires.
         * @param currentYear the year now.
         * @param currentMonth the month now.
         * @return true when the card's last good day has passed; false otherwise.
         */
        fun isExpired(
            month: Int,
            year: Int,
            currentYear: Int,
            currentMonth: Int,
        ): Boolean = year < currentYear || (year == currentYear && month < currentMonth)

        /** The century [parse] restores, which is the only one [format] can round-trip. */
        val SUPPORTED_YEARS: IntRange = 2000..2099

        /**
         * The current month, in the device's time zone. The only clock read in this file. `Calendar`
         * because the module's floor is API 23 with no core library desugaring, so `LocalDate` is not
         * on the device.
         *
         * @return the current month and year.
         */
        fun today(): ExpiryValue {
            val now = Calendar.getInstance()
            return ExpiryValue(
                month = now.get(Calendar.MONTH) + 1,
                year = now.get(Calendar.YEAR),
            )
        }

        /**
         * Parses month, any run of non-digits, then a two-digit year in this century. Any separator
         * `PayInFormatting` can produce reads back, so validation needs no configuration.
         *
         * @param text the expiry as typed.
         * @return the expiry, or null when the text is not one.
         */
        fun parse(text: String): ExpiryValue? {
            val digits = Regex("^\\s*(\\d{1,2})\\D+(\\d{2})\\s*$").find(text) ?: return null
            val month = digits.groupValues[1].toInt()
            val shortYear = digits.groupValues[2].toInt()
            if (month !in 1..12) return null
            return ExpiryValue(month, 2000 + shortYear)
        }
    }
}
