package com.payabli.sdk.payin.form

/**
 * Which years and months the expiry picker may offer.
 */
public object ExpiryChoices {
    /** How far ahead a card may expire. Longer than any card issued today, and bounded. */
    public const val YEARS_AHEAD: Int = 20

    /**
     * This year and the next [YEARS_AHEAD], stopping at the last year [ExpiryValue] can hold.
     *
     * Without the cap the list runs past 2099 from 2080 onwards, and picking one of those years
     * throws where the picker builds the value.
     */
    public fun years(today: ExpiryValue): List<Int> =
        (today.year..minOf(today.year + YEARS_AHEAD, ExpiryValue.SUPPORTED_YEARS.last)).toList()

    /**
     * Every month, except in the current year where the months already gone are dropped.
     *
     * A card is good through the end of its expiry month, so the current month is still offered.
     */
    public fun months(
        today: ExpiryValue,
        selectedYear: Int,
    ): List<Int> = if (selectedYear == today.year) (today.month..12).toList() else (1..12).toList()

    /**
     * Returns [month] if [available] contains it, otherwise the first month it does contain.
     */
    public fun coerceMonth(
        month: Int,
        available: List<Int>,
    ): Int = if (month in available) month else available.first()
}
