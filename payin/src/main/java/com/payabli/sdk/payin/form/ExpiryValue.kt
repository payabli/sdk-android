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
     * `MM/YY`, in [Locale.ROOT].
     *
     * The default locale renders non-ASCII digits under `ar-EG` and `hi-IN`, which [parse] cannot
     * read back.
     */
    public fun format(): String = String.format(Locale.ROOT, "%02d/%02d", month, year % 100)

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

        /** Parses `MM/YY`, taking a two-digit year as being in the current century. */
        public fun parse(text: String): ExpiryValue? {
            val parts = text.split("/")
            if (parts.size != 2) return null
            val month = parts[0].trim().toIntOrNull() ?: return null
            val shortYear = parts[1].trim().toIntOrNull() ?: return null
            if (month !in 1..12) return null
            if (shortYear !in 0..99) return null
            return ExpiryValue(month, 2000 + shortYear)
        }
    }
}
