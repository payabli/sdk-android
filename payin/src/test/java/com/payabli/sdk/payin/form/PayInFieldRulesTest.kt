package com.payabli.sdk.payin.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayInFieldRulesTest {
    // --- filtering ---

    @Test
    fun `digit fields drop everything that is not a digit`() {
        assertEquals("4111", PayInFieldRules.filter(PayInField.CardNumber, "4a1-1 1"))
        assertEquals("123", PayInFieldRules.filter(PayInField.CardSecurityCode, "1x2y3"))
        assertEquals("94-107", PayInFieldRules.filter(PayInField.CardPostalCode, "94-107"))
    }

    @Test
    fun `a pasted value is truncated to the field's limit`() {
        // A keyboard type is a hint: a paste, an autofill entry and a hardware keyboard all ignore
        // it, so the filter runs on the value.
        assertEquals("941071234", PayInFieldRules.filter(PayInField.CardPostalCode, "941071234"))
        assertEquals("1234", PayInFieldRules.filter(PayInField.CardSecurityCode, "123456"))
        assertEquals("021000021", PayInFieldRules.filter(PayInField.RoutingNumber, "0210000219999"))
    }

    @Test
    fun `text fields are left alone`() {
        assertEquals("Test O'Brien-Smith", PayInFieldRules.filter(PayInField.CardholderName, "Test O'Brien-Smith"))
    }

    @Test
    fun `an unlimited field is not truncated`() {
        val long = "a".repeat(200)
        assertEquals(long, PayInFieldRules.filter(PayInField.CardholderName, long))
    }

    // --- blank is not an error ---

    @Test
    fun `a blank value is never an error while it is still being typed`() {
        PayInField.entries.forEach { field ->
            assertNull("${field.name} complained about a blank", PayInFieldRules.error(field, ""))
        }
    }

    @Test
    fun `required fields report missing, the caller's own values do not`() {
        assertTrue(PayInFieldRules.missing(PayInField.CardNumber, ""))
        assertFalse(PayInFieldRules.missing(PayInField.CardNumber, "4111111111111111"))
        // The caller fixes these, so an empty one is not the payer's omission.
        assertFalse(PayInFieldRules.missing(PayInField.Amount, ""))
        assertFalse(PayInFieldRules.missing(PayInField.ServiceFee, ""))
        assertFalse(PayInFieldRules.missing(PayInField.MethodDescription, ""))
    }

    // --- postal code ---

    @Test
    fun `a postal code is length-limited and nothing more`() {
        // Requiring five digits rejects ZIP+4 and every Canadian and British code, all of which the
        // API accepts. A client rule that refuses a value the server would take is worse than none.
        listOf("94107", "94107-1234", "K1A 0B1", "SW1A 1AA", "1010").forEach {
            assertNull("$it was refused", PayInFieldRules.error(PayInField.CardPostalCode, it))
        }
    }

    @Test
    fun `a postal code is capped at the length the API takes`() {
        assertEquals(12, PayInFieldRules.filter(PayInField.CardPostalCode, "1".repeat(20)).length)
    }

    // --- security code ---

    @Test
    fun `three and four digit security codes are both accepted`() {
        assertNull(PayInFieldRules.error(PayInField.CardSecurityCode, "123"))
        assertNull(PayInFieldRules.error(PayInField.CardSecurityCode, "1234"))
    }

    @Test
    fun `a two digit security code is rejected, and carries both bounds`() {
        assertEquals(
            PayInFieldError.OutsideRange(3, 4),
            PayInFieldRules.error(PayInField.CardSecurityCode, "12"),
        )
    }

    // --- card number and Luhn ---

    @Test
    fun `the published test card passes`() {
        assertNull(PayInFieldRules.error(PayInField.CardNumber, "4111111111111111"))
    }

    @Test
    fun `a card number with a transposed pair fails the check digit`() {
        // 4111111111111111 with two digits swapped. This is the case Luhn exists to catch.
        assertEquals(
            PayInFieldError.CardNumberNotValid,
            PayInFieldRules.error(PayInField.CardNumber, "4111111111111121"),
        )
    }

    @Test
    fun `a card number that is too short says so before it says the check digit failed`() {
        assertEquals(
            PayInFieldError.ShorterThan(12),
            PayInFieldRules.error(PayInField.CardNumber, "411111"),
        )
    }

    @Test
    fun `Luhn accepts known-good numbers from several schemes`() {
        listOf(
            "4111111111111111",
            "5500005555555559",
            "378282246310005",
            "6011111111111117",
        ).forEach { assertTrue(it, PayInFieldRules.passesLuhn(it)) }
    }

    @Test
    fun `Luhn rejects an empty string and non-digits`() {
        assertFalse(PayInFieldRules.passesLuhn(""))
        assertFalse(PayInFieldRules.passesLuhn("4111-1111"))
    }

    // --- routing number ---

    @Test
    fun `the published test routing number passes the ABA checksum`() {
        assertNull(PayInFieldRules.error(PayInField.RoutingNumber, "021000021"))
    }

    @Test
    fun `a nine digit routing number with a bad checksum is rejected`() {
        assertEquals(
            PayInFieldError.RoutingNumberNotValid,
            PayInFieldRules.error(PayInField.RoutingNumber, "021000022"),
        )
    }

    @Test
    fun `a routing number of the wrong length is rejected on length first`() {
        assertEquals(
            PayInFieldError.NotExactly(9),
            PayInFieldRules.error(PayInField.RoutingNumber, "02100002"),
        )
    }

    @Test
    fun `the ABA checksum rejects the wrong length outright`() {
        assertFalse(PayInFieldRules.passesAbaChecksum("02100002"))
        assertFalse(PayInFieldRules.passesAbaChecksum("0210000211"))
    }

    // --- account number ---

    @Test
    fun `account numbers between four and seventeen digits are accepted`() {
        assertNull(PayInFieldRules.error(PayInField.AccountNumber, "1111"))
        assertNull(PayInFieldRules.error(PayInField.AccountNumber, "1".repeat(17)))
    }

    @Test
    fun `a three digit account number is rejected`() {
        assertEquals(
            PayInFieldError.OutsideRange(4, 17),
            PayInFieldRules.error(PayInField.AccountNumber, "111"),
        )
    }

    // --- email ---

    @Test
    fun `ordinary addresses are accepted`() {
        listOf(
            "test.cardholder@example.com",
            "a@b.co",
            "first+tag@sub.domain.org",
        ).forEach { assertNull(it, PayInFieldRules.error(PayInField.BillingEmail, it)) }
    }

    @Test
    fun `addresses missing a part are rejected`() {
        listOf("nope", "@example.com", "a@b", "a@@b.com", "a@.com", "a@b.").forEach {
            assertEquals(it, PayInFieldError.EmailNotValid, PayInFieldRules.error(PayInField.BillingEmail, it))
        }
    }

    // --- expiry ---

    @Test
    fun `an expiry is formatted as two digit month and year`() {
        assertEquals("07/30", ExpiryValue(7, 2030).format())
        assertEquals("12/09", ExpiryValue(12, 2009).format())
    }

    @Test
    fun `an expiry round-trips through format and parse`() {
        val original = ExpiryValue(3, 2031)
        assertEquals(original, ExpiryValue.parse(original.format()))
    }

    @Test
    fun `the separator reaches the value and the value still parses`() {
        // A form set to "-" has to show MM-YY and write 07-30, and read that back.
        val expiry = ExpiryValue(7, 2030)
        listOf("-", ".", " / ", "%s").forEach { separator ->
            val written = expiry.format(separator)
            assertEquals(separator, "07${separator}30", written)
            assertEquals(separator, expiry, ExpiryValue.parse(written))
        }
    }

    @Test
    fun `a value that is not a month and a year does not parse`() {
        listOf("", "0730", "13/30", "00/30", "7/3", "//", "07/", "/30", "07/30/31").forEach {
            assertEquals(it, null, ExpiryValue.parse(it))
        }
    }

    @Test
    fun `an expiry round-trips under a locale whose digits are not ASCII`() {
        // The default locale would decide the digits, and parse reads only ASCII, so the value would
        // not survive its own format on a device set to one of these.
        val original = java.util.Locale.getDefault()
        try {
            listOf(java.util.Locale.forLanguageTag("ar-EG"), java.util.Locale.forLanguageTag("hi-IN")).forEach {
                java.util.Locale.setDefault(it)
                val expiry = ExpiryValue(7, 2030)
                assertEquals(it.toString(), "07/30", expiry.format())
                assertEquals(it.toString(), expiry, ExpiryValue.parse(expiry.format()))
            }
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun `a malformed expiry does not parse`() {
        listOf("", "07", "07/", "/30", "13/30", "ab/cd", "07/30/31").forEach {
            assertNull(it, ExpiryValue.parse(it))
        }
    }

    @Test
    fun `a card is still good during its expiry month`() {
        // The off-by-one that would reject a valid card on the day someone tried to use it.
        assertFalse(ExpiryValue(8, 2026).isExpired(currentYear = 2026, currentMonth = 8))
    }

    @Test
    fun `a card is expired the month after`() {
        assertTrue(ExpiryValue(8, 2026).isExpired(currentYear = 2026, currentMonth = 9))
    }

    @Test
    fun `a card from a previous year is expired`() {
        assertTrue(ExpiryValue(12, 2025).isExpired(currentYear = 2026, currentMonth = 1))
    }

    @Test
    fun `a card from a later year is not expired`() {
        assertFalse(ExpiryValue(1, 2027).isExpired(currentYear = 2026, currentMonth = 12))
    }

    @Test
    fun `an expired card is reported when the clock is supplied`() {
        val today = ExpiryValue(9, 2026)
        assertEquals(
            PayInFieldError.ExpiryPast,
            PayInFieldRules.error(PayInField.CardExpiration, "08/26", today),
        )
        assertNull(PayInFieldRules.error(PayInField.CardExpiration, "09/26", today))
    }

    @Test
    fun `without a clock the expiry is only checked for shape`() {
        assertNull(PayInFieldRules.error(PayInField.CardExpiration, "01/20"))
        assertEquals(
            PayInFieldError.ExpiryIncomplete,
            PayInFieldRules.error(PayInField.CardExpiration, "nonsense"),
        )
    }

    // --- what the messages are built from ---

    @Test
    fun `an error carries the numbers its message needs`() {
        // The Compose layer formats these into text. A case that lost its bounds would leave the
        // wording to guess them, which is how "3 to 4 digits" drifts away from the rule.
        assertEquals(
            12,
            (PayInFieldRules.error(PayInField.CardNumber, "411111") as PayInFieldError.ShorterThan).minimum,
        )
        assertEquals(
            9,
            (PayInFieldRules.error(PayInField.RoutingNumber, "0210") as PayInFieldError.NotExactly).length,
        )
        val range = PayInFieldRules.error(PayInField.AccountNumber, "111") as PayInFieldError.OutsideRange
        assertEquals(4, range.minimum)
        assertEquals(17, range.maximum)
    }

    @Test
    fun `a non-digit in a digits-only field is reported as such`() {
        // Reachable through a paste that bypasses filter, so the rule answers for it.
        assertEquals(
            PayInFieldError.DigitsOnly,
            PayInFieldRules.error(PayInField.RoutingNumber, "02100002a"),
        )
    }

    @Test
    fun `every field with a length limit filters to it`() {
        PayInField.entries.forEach { field ->
            val limit = PayInFieldRules.maxLength(field) ?: return@forEach
            assertEquals(
                "${field.name} filtered past its own limit",
                limit,
                PayInFieldRules.filter(field, "1".repeat(limit + 5)).length,
            )
        }
    }

    @Test
    fun `no field is both a choice and length limited`() {
        // A dropdown with a character limit is a contradiction: nothing is typed into it.
        PayInField.entries
            .filter { it.input == PayInFieldInput.Choice }
            .forEach { assertNull("${it.name} is a choice with a limit", PayInFieldRules.maxLength(it)) }
    }

    // --- card brand ---

    @Test
    fun `the published test numbers are detected as their schemes`() {
        assertEquals(CardBrand.Visa, CardBrand.of("4111111111111111"))
        assertEquals(CardBrand.Mastercard, CardBrand.of("5500005555555559"))
        assertEquals(CardBrand.Mastercard, CardBrand.of("2223003122003222"))
        assertEquals(CardBrand.AmericanExpress, CardBrand.of("378282246310005"))
        assertEquals(CardBrand.Discover, CardBrand.of("6011111111111117"))
    }

    @Test
    fun `a brand is decided from a prefix, because the field asks while it is still being typed`() {
        assertEquals(CardBrand.Visa, CardBrand.of("4"))
        assertEquals(CardBrand.Mastercard, CardBrand.of("55"))
        assertEquals(CardBrand.AmericanExpress, CardBrand.of("37"))
    }

    @Test
    fun `too few digits to tell, and a number in no range, both read as unknown`() {
        assertEquals(CardBrand.Unknown, CardBrand.of(""))
        assertEquals(CardBrand.Unknown, CardBrand.of("2"))
        assertEquals(CardBrand.Unknown, CardBrand.of("9999999999999999"))
    }

    @Test
    fun `separators do not change the brand`() {
        assertEquals(CardBrand.Visa, CardBrand.of("4111 1111 1111 1111"))
    }

    @Test
    fun `the mastercard 2-series boundaries are inclusive at both ends`() {
        assertEquals(CardBrand.Mastercard, CardBrand.of("2221000000000000"))
        assertEquals(CardBrand.Mastercard, CardBrand.of("2720000000000000"))
        assertEquals(CardBrand.Unknown, CardBrand.of("2220000000000000"))
        assertEquals(CardBrand.Unknown, CardBrand.of("2721000000000000"))
    }

    @Test
    fun `a value that is not a brand is still a number the rules will judge`() {
        // Detection and validation are separate questions, and an unknown brand is not an error.
        assertEquals(CardBrand.Unknown, CardBrand.of("9999999999999999"))
        assertNotNull(PayInFieldRules.error(PayInField.CardNumber, "9999999999999999"))
    }
}
