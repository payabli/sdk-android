package com.payabli.sdk.payin.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edges of the public surface, where a value a caller supplies meets a rule that reads it.
 *
 * Each of these is reachable from the public API and none of them is reachable from the form, which
 * filters and formats before a rule ever sees the value.
 */
class PayInApiBoundsTest {
    // --- the separator has to survive its own round trip ---

    @Test
    fun `a separator of digits is refused where it is configured`() {
        // "1" writes 07130, which parse reads as no month and year at all, so the picker's own value
        // fails validation and the form can never submit.
        listOf("1", "0", "-1-", "9").forEach { separator ->
            val failed =
                try {
                    PayInFormatting(expirySeparator = separator)
                    false
                } catch (expected: IllegalArgumentException) {
                    true
                }
            assertTrue("a separator of \"$separator\" was accepted", failed)
        }
    }

    @Test
    fun `every separator the configuration accepts round-trips`() {
        val expiry = ExpiryValue(7, 2030)
        listOf("/", "-", ".", " / ", "%s", " ").forEach { separator ->
            val formatting = PayInFormatting(expirySeparator = separator)
            val written = expiry.format(formatting.expirySeparator)
            assertEquals(separator, expiry, ExpiryValue.parse(written))
            assertNull(
                "$written was produced by the picker and does not validate",
                PayInFieldRules.error(PayInField.CardExpiration, written, ExpiryValue(1, 2026)),
            )
        }
    }

    // --- one alphabet for filtering and for validating ---

    @Test
    fun `a non-ASCII digit is dropped rather than kept and then called a non-digit`() {
        // Char.isDigit keeps these. The rules read ASCII only, so keeping them means a payer sees
        // "Digits only" over a field holding nothing but digits, and no edit clears it.
        listOf("١٢٣٤", "١٢٣٤٥٦٧٨٩٠١٢", "٤4٤4").forEach { arabicIndic ->
            val filtered = PayInFieldRules.filter(PayInField.CardNumber, arabicIndic)
            assertTrue("$filtered kept a non-ASCII digit", filtered.all { it in '0'..'9' })
            assertNotEquals(
                PayInFieldError.DigitsOnly,
                PayInFieldRules.error(PayInField.CardNumber, filtered),
            )
        }
    }

    @Test
    fun `the same holds for every digits-only field`() {
        PayInField.entries.filter { PayInFieldRules.isDigitsOnly(it) }.forEach { field ->
            val filtered = PayInFieldRules.filter(field, "١٢٣٤")
            assertEquals("${field.name} kept a non-ASCII digit", "", filtered)
        }
    }

    // --- both ends of the card number ---

    @Test
    fun `a card number past the maximum is an error, not only truncated by the field`() {
        // error is public, so a caller reaches it with a value the field's filter never saw.
        val twenty = "41111111111111111115"
        assertTrue("the fixture has to pass Luhn, or it fails for the wrong reason", PayInFieldRules.passesLuhn(twenty))
        assertEquals(PayInFieldError.LongerThan(19), PayInFieldRules.error(PayInField.CardNumber, twenty))
    }

    @Test
    fun `the accepted lengths are the ones the field will hold`() {
        val maximum = PayInFieldRules.maxLength(PayInField.CardNumber)!!
        assertNull(PayInFieldRules.error(PayInField.CardNumber, "4111111111111111"))
        assertEquals(19, maximum)
        assertNull(
            "a number of exactly maxLength is refused by the rule that sets it",
            PayInFieldRules.error(PayInField.CardNumber, "4" + "0".repeat(maximum - 2) + "6"),
        )
    }

    // --- equality has to follow the copies, or Compose skips a change ---

    @Test
    fun `configurations built either side of a mutation are not equal`() {
        val shared = mutableListOf(PayInMethodType.Card)
        val before = PayInFormConfiguration(allowedMethods = shared)
        shared += PayInMethodType.BankAccount
        val after = PayInFormConfiguration(allowedMethods = shared)

        assertEquals(listOf(PayInMethodType.Card), before.methodsOffered)
        assertEquals(listOf(PayInMethodType.Card, PayInMethodType.BankAccount), after.methodsOffered)
        assertNotEquals("equal while the form reads different fields from each", before, after)
        assertNotEquals(before.hashCode(), after.hashCode())
    }

    @Test
    fun `labels built either side of a mutation are not equal`() {
        val shared = mutableMapOf(PayInField.CardNumber to "Card")
        val before = PayInFormLabels(fieldLabels = shared)
        shared[PayInField.CardNumber] = "Something else"
        val after = PayInFormLabels(fieldLabels = shared)

        assertEquals("Card", before.labelFor(PayInField.CardNumber))
        assertEquals("Something else", after.labelFor(PayInField.CardNumber))
        assertNotEquals("equal while the form reads different wording from each", before, after)
    }

    @Test
    fun `two configurations describing the same form are still equal`() {
        // The other half. Equality that only ever said "different" would make every recomposition
        // rebuild the form.
        assertEquals(PayInFormConfiguration(), PayInFormConfiguration())
        assertEquals(PayInFormConfiguration().hashCode(), PayInFormConfiguration().hashCode())
        assertEquals(PayInFormLabels(title = "Pay"), PayInFormLabels(title = "Pay"))
        assertEquals(
            PayInFormConfiguration(allowedMethods = listOf(PayInMethodType.Card)),
            PayInFormConfiguration(allowedMethods = mutableListOf(PayInMethodType.Card)),
        )
    }

    @Test
    fun `a section's field list is copied too`() {
        val fields = mutableListOf(PayInField.CardNumber)
        val configuration = PayInFormConfiguration(cardSections = listOf(PayInFormSection(fields = fields)))
        fields += PayInField.CardholderName

        assertEquals(
            listOf(PayInField.CardNumber),
            configuration.sectionsFor(PayInMethodType.Card).single().fields,
        )
    }
}
