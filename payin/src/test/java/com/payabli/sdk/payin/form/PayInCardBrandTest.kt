package com.payabli.sdk.payin.form

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which scheme a number names, per scheme and at the edges of every range.
 *
 * The ranges overlap where one prefix sits inside another, so an order that looks arbitrary is the whole of the
 * correctness here: `65` is Discover while `62` is UnionPay, and Diners' `3095` sits inside JCB's territory.
 * A wrong answer is shown to the payer beside their own card number.
 */
class PayInCardBrandTest {
    private fun brandOf(number: String) = PayInCardBrand.of(number)

    @Test
    fun `each scheme is named by its own prefixes`() {
        mapOf(
            "4111111111111111" to PayInCardBrand.Visa,
            "4000000000000002" to PayInCardBrand.Visa,
            "5555555555554444" to PayInCardBrand.Mastercard,
            "5105105105105100" to PayInCardBrand.Mastercard,
            "2221000000000009" to PayInCardBrand.Mastercard,
            "2720999999999999" to PayInCardBrand.Mastercard,
            "378282246310005" to PayInCardBrand.AmericanExpress,
            "341111111111111" to PayInCardBrand.AmericanExpress,
            "6011111111111117" to PayInCardBrand.Discover,
            "6511111111111119" to PayInCardBrand.Discover,
            "6441111111111116" to PayInCardBrand.Discover,
            "30569309025904" to PayInCardBrand.DinersClub,
            "3095111111111111" to PayInCardBrand.DinersClub,
            "36227206271667" to PayInCardBrand.DinersClub,
            "3852000002323" to PayInCardBrand.DinersClub,
            "3530111333300000" to PayInCardBrand.Jcb,
            "3589111111111111" to PayInCardBrand.Jcb,
            "6212345678901232" to PayInCardBrand.UnionPay,
        ).forEach { (number, expected) ->
            assertEquals(number, expected, brandOf(number))
        }
    }

    @Test
    fun `the edges of each range belong to the range`() {
        // Off by one at either end shows the wrong scheme for a real card, and the middle of a range would
        // never catch it.
        assertEquals(PayInCardBrand.Mastercard, brandOf("5100000000000000"))
        assertEquals(PayInCardBrand.Mastercard, brandOf("5599999999999999"))
        assertEquals(PayInCardBrand.Mastercard, brandOf("2221000000000000"))
        assertEquals(PayInCardBrand.Mastercard, brandOf("2720000000000000"))
        assertEquals(PayInCardBrand.Discover, brandOf("6440000000000000"))
        assertEquals(PayInCardBrand.Discover, brandOf("6499999999999999"))
        assertEquals(PayInCardBrand.DinersClub, brandOf("3000000000000"))
        assertEquals(PayInCardBrand.DinersClub, brandOf("3059999999999"))
        assertEquals(PayInCardBrand.Jcb, brandOf("3528000000000000"))
        assertEquals(PayInCardBrand.Jcb, brandOf("3589999999999999"))
    }

    @Test
    fun `a prefix just outside a range is not that scheme`() {
        assertEquals(PayInCardBrand.Unknown, brandOf("5000000000000000"))
        assertEquals(PayInCardBrand.Unknown, brandOf("5600000000000000"))
        assertEquals(PayInCardBrand.Unknown, brandOf("2220000000000000"))
        assertEquals(PayInCardBrand.Unknown, brandOf("2721000000000000"))
        assertEquals(PayInCardBrand.Unknown, brandOf("3060000000000"))
        assertEquals(PayInCardBrand.Unknown, brandOf("3527000000000000"))
        assertEquals(PayInCardBrand.Unknown, brandOf("3590000000000000"))
    }

    @Test
    fun `a grouped number reads the same as a bare one`() {
        // The field groups the digits as they are typed, and the value the badge reads carries the spaces.
        assertEquals(PayInCardBrand.Visa, brandOf("4111 1111 1111 1111"))
        assertEquals(PayInCardBrand.Mastercard, brandOf("5555 5555 5555 4444"))
        assertEquals(PayInCardBrand.AmericanExpress, brandOf("3782 822463 10005"))
    }

    @Test
    fun `a number too short to tell names nothing, except a Visa's first digit`() {
        // Visa is the one scheme a single digit settles, and showing it from the first keystroke is what a
        // payer sees on every other form.
        assertEquals(PayInCardBrand.Unknown, brandOf(""))
        assertEquals(PayInCardBrand.Unknown, brandOf("3"))
        assertEquals(PayInCardBrand.Unknown, brandOf("5"))
        assertEquals(PayInCardBrand.Visa, brandOf("4"))
    }

    @Test
    fun `anything that is not a digit is not read as one`() {
        // The value arrives as the payer left it, and parsing a letter as a prefix throws.
        assertEquals(PayInCardBrand.Unknown, brandOf("abcd"))
        assertEquals(PayInCardBrand.Visa, brandOf("4a1b1c"))
        assertEquals(PayInCardBrand.Unknown, brandOf("-"))
    }

    @Test
    fun `every scheme has a name to show, and Unknown has none`() {
        PayInCardBrand.entries
            .filter { it != PayInCardBrand.Unknown }
            .forEach { assertEquals("${it.name} shows nothing", true, it.display.isNotBlank()) }
        assertEquals("", PayInCardBrand.Unknown.display)
    }
}
