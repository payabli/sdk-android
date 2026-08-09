package com.payabli.sdk.payin.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picker cannot offer a past date. Checkable here because the bounds are not in the composable. */
class ExpiryChoicesTest {
    private val august2026 = ExpiryValue(month = 8, year = 2026)

    // --- years ---

    @Test
    fun `the year list starts at this year`() {
        assertEquals(2026, ExpiryChoices.years(august2026).first())
    }

    @Test
    fun `no past year is offered, in any year`() {
        (2026..2050).forEach { year ->
            val today = ExpiryValue(month = 6, year = year)
            assertTrue(
                "a year before $year was offered",
                ExpiryChoices.years(today).none { it < year },
            )
        }
    }

    @Test
    fun `the year list is bounded and covers longer than any card is issued for`() {
        val years = ExpiryChoices.years(august2026)
        assertEquals(ExpiryChoices.YEARS_AHEAD + 1, years.size)
        assertEquals(2046, years.last())
    }

    // --- months ---

    @Test
    fun `in the current year the months already gone are not offered`() {
        assertEquals((8..12).toList(), ExpiryChoices.months(august2026, selectedYear = 2026))
    }

    @Test
    fun `the current month is still offered, because a card is good to the end of it`() {
        // The off-by-one that would refuse a card that is still valid today.
        assertTrue(ExpiryChoices.months(august2026, selectedYear = 2026).contains(8))
    }

    @Test
    fun `a later year offers all twelve months`() {
        assertEquals((1..12).toList(), ExpiryChoices.months(august2026, selectedYear = 2027))
    }

    @Test
    fun `in January nothing is dropped, since no month has gone yet`() {
        val january = ExpiryValue(month = 1, year = 2027)
        assertEquals((1..12).toList(), ExpiryChoices.months(january, selectedYear = 2027))
    }

    @Test
    fun `in December only December is left`() {
        val december = ExpiryValue(month = 12, year = 2026)
        assertEquals(listOf(12), ExpiryChoices.months(december, selectedYear = 2026))
    }

    // --- the two together ---

    @Test
    fun `no month and year the picker can offer is ever in the past`() {
        // The property the whole picker rests on, checked across every combination it can produce
        // from twenty-five different starting months.
        (2026..2050).forEach { year ->
            (1..12).forEach { month ->
                val today = ExpiryValue(month, year)
                ExpiryChoices.years(today).forEach { offeredYear ->
                    ExpiryChoices.months(today, offeredYear).forEach { offeredMonth ->
                        assertFalse(
                            "$offeredMonth/$offeredYear is in the past on $month/$year",
                            ExpiryValue(offeredMonth, offeredYear).isExpired(year, month),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `every offered combination also passes validation`() {
        val today = august2026
        ExpiryChoices.years(today).forEach { year ->
            ExpiryChoices.months(today, year).forEach { month ->
                val value = ExpiryValue(month, year).format()
                assertEquals(
                    "$value was offered but does not validate",
                    null,
                    PayInFieldRules.error(PayInField.CardExpiration, value, today),
                )
            }
        }
    }

    // --- coercion ---

    @Test
    fun `a month still in range is left alone`() {
        assertEquals(10, ExpiryChoices.coerceMonth(10, (8..12).toList()))
    }

    @Test
    fun `a month that fell out of range moves to the first one available`() {
        // Pick March 2028, then change the year back to this one: March is no longer offered, and
        // leaving it selected would show a month the list does not contain.
        assertEquals(8, ExpiryChoices.coerceMonth(3, (8..12).toList()))
    }

    @Test
    fun `coercion never returns a month outside the list`() {
        (1..12).forEach { month ->
            val available = (8..12).toList()
            assertTrue(ExpiryChoices.coerceMonth(month, available) in available)
        }
    }
}
