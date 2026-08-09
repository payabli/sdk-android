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

    // --- a month outside 1..12 reaches three things that assume it is inside ---

    @Test
    fun `an impossible month is refused where the value is built`() {
        listOf(0, 13, -1, 99).forEach { month ->
            val failed =
                try {
                    ExpiryValue(month, 2030)
                    false
                } catch (expected: IllegalArgumentException) {
                    true
                }
            assertTrue("month $month was accepted", failed)
        }
    }

    @Test
    fun `every month the picker offers builds a value, and every built value round-trips`() {
        // The three that assumed it: format wrote 13/30, parse refused its own output, and months
        // returned an empty list that coerceMonth reads the first element of.
        val today = ExpiryValue(8, 2026)
        ExpiryChoices.years(today).forEach { year ->
            val months = ExpiryChoices.months(today, year)
            assertTrue("no months offered for $year", months.isNotEmpty())
            months.forEach { month ->
                val value = ExpiryValue(month, year)
                assertEquals(value, ExpiryValue.parse(value.format()))
            }
        }
    }

    @Test
    fun `a year outside the century format can write is refused`() {
        listOf(1999, 2100, 0, -1).forEach { year ->
            val failed =
                try {
                    ExpiryValue(1, year)
                    false
                } catch (expected: IllegalArgumentException) {
                    true
                }
            assertTrue("year $year was accepted", failed)
        }
    }

    // --- copy() has to follow the copies, as equals does ---

    @Test
    fun `a no-argument copy equals its source after the caller mutates what they passed in`() {
        // The generated copy of a data class rebuilds from the constructor properties, so it would
        // pick up the mutation while the source keeps its snapshot, and the two would differ.
        val methods = mutableListOf(PayInMethodType.Card)
        val labels = mutableMapOf(PayInField.CardNumber to "Card")
        val configuration = PayInFormConfiguration(allowedMethods = methods)
        val wording = PayInFormLabels(fieldLabels = labels)

        methods += PayInMethodType.BankAccount
        labels[PayInField.CardNumber] = "Something else"

        assertEquals(configuration, configuration.copy())
        assertEquals(wording, wording.copy())
        assertEquals(listOf(PayInMethodType.Card), configuration.copy().methodsOffered)
        assertEquals("Card", wording.copy().labelFor(PayInField.CardNumber))
    }

    @Test
    fun `copy changes the one value it is given and nothing else`() {
        val configuration = PayInFormConfiguration(requiredFields = setOf(PayInField.Amount))
        val moved = configuration.copy(labelLayout = PayInLabelLayout.Placeholder)

        assertEquals(PayInLabelLayout.Placeholder, moved.labelLayout)
        assertTrue(moved.isRequired(PayInField.Amount))
        assertEquals(configuration.methodsOffered, moved.methodsOffered)
        assertNotEquals(configuration, moved)
    }

    @Test
    fun `a section copies the field list it is given`() {
        val fields = mutableListOf(PayInField.CardNumber)
        val section = PayInFormSection(fields = fields)
        fields += PayInField.CardholderName

        assertEquals(listOf(PayInField.CardNumber), section.fields)
        assertEquals(section, section.copy())
    }

    @Test
    fun `reported values are a copy of what the form held`() {
        val held = mutableMapOf(PayInField.CardNumber to "4111111111111111")
        val reported = PayInFormValues(PayInMethodType.Card, held)
        held[PayInField.CardNumber] = "something else"

        assertEquals("4111111111111111", reported[PayInField.CardNumber])
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
