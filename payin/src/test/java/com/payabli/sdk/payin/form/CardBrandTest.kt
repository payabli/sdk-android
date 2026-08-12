package com.payabli.sdk.payin.form

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which scheme a number names, per scheme and at the edges of every range.
 *
 * The ranges overlap where one sits inside another, and the order that resolves them is the whole of the
 * correctness here: Discover's `622126-622925` is inside UnionPay's `62`, and Diners Club's `3095` is inside
 * JCB's leading `3`. A wrong answer is shown to the payer beside their own card number.
 *
 * `PayInApiBoundsTest` covers the four schemes this detector shipped with; these cover the three added for the
 * badge and the boundaries between all seven.
 */
class CardBrandTest {
    @Test
    fun `each scheme is named by its own prefixes`() {
        mapOf(
            "4111111111111111" to CardBrand.Visa,
            "5555555555554444" to CardBrand.Mastercard,
            "2221000000000009" to CardBrand.Mastercard,
            "378282246310005" to CardBrand.AmericanExpress,
            "341111111111111" to CardBrand.AmericanExpress,
            "6011111111111117" to CardBrand.Discover,
            "6511111111111119" to CardBrand.Discover,
            "6441111111111116" to CardBrand.Discover,
            "30569309025904" to CardBrand.DinersClub,
            "3095111111111111" to CardBrand.DinersClub,
            "36227206271667" to CardBrand.DinersClub,
            "3852000002323" to CardBrand.DinersClub,
            "3530111333300000" to CardBrand.Jcb,
            "3589111111111111" to CardBrand.Jcb,
            "6212345678901232" to CardBrand.UnionPay,
        ).forEach { (number, expected) -> assertEquals(number, expected, CardBrand.of(number)) }
    }

    @Test
    fun `Discover keeps the UnionPay range it acquired`() {
        // 622126-622925 is issued on Discover's network and reads as Discover on every other payment form.
        // Reading it as UnionPay because it starts 62 is what the ordering prevents.
        assertEquals(CardBrand.Discover, CardBrand.of("6221260000000000"))
        assertEquals(CardBrand.Discover, CardBrand.of("6229250000000000"))
        assertEquals(CardBrand.UnionPay, CardBrand.of("6221250000000000"))
        assertEquals(CardBrand.UnionPay, CardBrand.of("6229260000000000"))
    }

    @Test
    fun `the edges of each range belong to the range`() {
        // Off by one at either end shows the wrong scheme for a real card, and the middle of a range would
        // never catch it.
        assertEquals(CardBrand.Mastercard, CardBrand.of("5100000000000000"))
        assertEquals(CardBrand.Mastercard, CardBrand.of("5599999999999999"))
        assertEquals(CardBrand.Mastercard, CardBrand.of("2221000000000000"))
        assertEquals(CardBrand.Mastercard, CardBrand.of("2720000000000000"))
        assertEquals(CardBrand.Discover, CardBrand.of("6440000000000000"))
        assertEquals(CardBrand.Discover, CardBrand.of("6499999999999999"))
        assertEquals(CardBrand.DinersClub, CardBrand.of("3000000000000"))
        assertEquals(CardBrand.DinersClub, CardBrand.of("3059999999999"))
        assertEquals(CardBrand.Jcb, CardBrand.of("3528000000000000"))
        assertEquals(CardBrand.Jcb, CardBrand.of("3589999999999999"))
    }

    @Test
    fun `a prefix just outside a range is not that scheme`() {
        assertEquals(CardBrand.Unknown, CardBrand.of("5000000000000000"))
        assertEquals(CardBrand.Unknown, CardBrand.of("5600000000000000"))
        assertEquals(CardBrand.Unknown, CardBrand.of("2220000000000000"))
        assertEquals(CardBrand.Unknown, CardBrand.of("2721000000000000"))
        assertEquals(CardBrand.Unknown, CardBrand.of("3060000000000"))
        assertEquals(CardBrand.Unknown, CardBrand.of("3527000000000000"))
        assertEquals(CardBrand.Unknown, CardBrand.of("3590000000000000"))
    }

    @Test
    fun `a grouped number reads the same as a bare one`() {
        // The field groups the digits as they are typed, and the value the badge reads carries the spaces.
        assertEquals(CardBrand.Visa, CardBrand.of("4111 1111 1111 1111"))
        assertEquals(CardBrand.Mastercard, CardBrand.of("5555 5555 5555 4444"))
        assertEquals(CardBrand.AmericanExpress, CardBrand.of("3782 822463 10005"))
    }

    @Test
    fun `a number too short to tell names nothing, except a Visa's first digit`() {
        // Visa is the one scheme a single digit settles, and showing it from the first keystroke is what a
        // payer sees on every other form.
        assertEquals(CardBrand.Unknown, CardBrand.of(""))
        assertEquals(CardBrand.Unknown, CardBrand.of("3"))
        assertEquals(CardBrand.Unknown, CardBrand.of("6"))
        assertEquals(CardBrand.Visa, CardBrand.of("4"))
    }

    @Test
    fun `anything that is not an ASCII digit is not read as one`() {
        // The value arrives as the payer left it, and parsing a letter as a prefix throws.
        assertEquals(CardBrand.Unknown, CardBrand.of("abcd"))
        assertEquals(CardBrand.Visa, CardBrand.of("4a1b1c"))
        assertEquals(CardBrand.Unknown, CardBrand.of("-"))
        // Arabic-Indic four, which the field's own rules refuse.
        assertEquals(CardBrand.Unknown, CardBrand.of("٤111"))
    }
}
