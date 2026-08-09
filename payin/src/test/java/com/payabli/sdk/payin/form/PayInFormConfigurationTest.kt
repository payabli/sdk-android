package com.payabli.sdk.payin.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The configuration corrects what it is handed, and each correction is a state the form cannot draw.
 *
 * A caller builds one of these from their own data, so "no allowed methods" and "a default that is
 * not allowed" both arrive in practice. Left alone they render a form with no fields, or a selector
 * pointing at a tab that is not there.
 */
class PayInFormConfigurationTest {
    @Test
    fun `an empty method list falls back to the default, so there is always something to fill in`() {
        val configuration =
            PayInFormConfiguration(allowedMethods = emptyList(), defaultMethod = PayInMethodType.BankAccount)

        assertEquals(listOf(PayInMethodType.BankAccount), configuration.methodsOffered)
        assertEquals(PayInMethodType.BankAccount, configuration.startingMethod)
    }

    @Test
    fun `a default outside the allowed set is replaced by one that is in it`() {
        val configuration =
            PayInFormConfiguration(
                allowedMethods = listOf(PayInMethodType.BankAccount),
                defaultMethod = PayInMethodType.Card,
            )

        assertEquals(PayInMethodType.BankAccount, configuration.startingMethod)
    }

    @Test
    fun `a method listed twice is offered once`() {
        val configuration =
            PayInFormConfiguration(
                allowedMethods = listOf(PayInMethodType.Card, PayInMethodType.Card, PayInMethodType.BankAccount),
            )

        assertEquals(listOf(PayInMethodType.Card, PayInMethodType.BankAccount), configuration.methodsOffered)
    }

    @Test
    fun `a default that is allowed is left alone`() {
        assertEquals(PayInMethodType.Card, PayInFormConfiguration().startingMethod)
    }

    // --- sections ---

    @Test
    fun `a field repeated across sections is rendered once`() {
        // Two fields bound to the same value would both accept typing and disagree about it.
        val configuration =
            PayInFormConfiguration(
                cardSections =
                    listOf(
                        PayInFormSection(fields = listOf(PayInField.CardNumber, PayInField.CardholderName)),
                        PayInFormSection(fields = listOf(PayInField.CardNumber, PayInField.CardPostalCode)),
                    ),
            )

        assertEquals(
            listOf(PayInField.CardNumber, PayInField.CardholderName, PayInField.CardPostalCode),
            configuration.inputFieldsFor(PayInMethodType.Card),
        )
    }

    @Test
    fun `a section left with no fields is dropped rather than drawn as a bare heading`() {
        val configuration =
            PayInFormConfiguration(
                cardSections =
                    listOf(
                        PayInFormSection(fields = listOf(PayInField.CardNumber)),
                        PayInFormSection(title = "Empty", fields = listOf(PayInField.CardNumber)),
                    ),
            )

        assertEquals(1, configuration.sectionsFor(PayInMethodType.Card).size)
    }

    @Test
    fun `each instrument gets its own sections`() {
        val configuration = PayInFormConfiguration()
        val card = configuration.inputFieldsFor(PayInMethodType.Card)
        val bank = configuration.inputFieldsFor(PayInMethodType.BankAccount)

        assertTrue(card.contains(PayInField.CardNumber))
        assertFalse(card.contains(PayInField.RoutingNumber))
        assertTrue(bank.contains(PayInField.RoutingNumber))
        assertFalse(bank.contains(PayInField.CardNumber))
    }

    @Test
    fun `a summary section is not a field the payer types into`() {
        val configuration =
            PayInFormConfiguration(
                cardSections =
                    PayInFormConfiguration.defaultCardSections() +
                        PayInFormSection(
                            fields = listOf(PayInField.Amount, PayInField.ServiceFee),
                            style = PayInSectionStyle.Summary,
                        ),
            )

        assertFalse(configuration.inputFieldsFor(PayInMethodType.Card).contains(PayInField.Amount))
        assertEquals(2, configuration.sectionsFor(PayInMethodType.Card).size)
    }

    // --- labels ---

    @Test
    fun `a section with no title of its own takes one from resources`() {
        // Null and not an English default, so an unconfigured form is still translated.
        PayInFormConfiguration().sectionsFor(PayInMethodType.Card).forEach { assertNull(it.title) }
    }

    @Test
    fun `labels default to the resource, and a blank override does not win`() {
        val labels = PayInFormLabels(fieldLabels = mapOf(PayInField.CardNumber to "  "))
        assertNull(labels.labelFor(PayInField.CardNumber))
        assertNull(labels.labelFor(PayInField.CardholderName))
        assertEquals(
            "Card",
            PayInFormLabels(fieldLabels = mapOf(PayInField.CardNumber to "Card")).labelFor(PayInField.CardNumber),
        )
    }

    @Test
    fun `an external layout shows labels and a placeholder layout does not`() {
        assertTrue(PayInFormConfiguration().showsLabelFor(PayInField.CardNumber))
        assertFalse(
            PayInFormConfiguration(labelLayout = PayInLabelLayout.Placeholder).showsLabelFor(PayInField.CardNumber),
        )
    }

    @Test
    fun `a field named as hidden loses its label and keeps its field`() {
        val configuration = PayInFormConfiguration(hiddenFieldLabels = setOf(PayInField.CardNumber))

        assertFalse(configuration.showsLabelFor(PayInField.CardNumber))
        assertTrue(configuration.showsLabelFor(PayInField.CardholderName))
        assertTrue(configuration.inputFieldsFor(PayInMethodType.Card).contains(PayInField.CardNumber))
    }

    // --- required ---

    @Test
    fun `the rules decide what is required, and a caller can add to it`() {
        val configuration = PayInFormConfiguration(requiredFields = setOf(PayInField.Amount))

        assertTrue(configuration.isRequired(PayInField.CardNumber))
        assertTrue("the caller asked for it", configuration.isRequired(PayInField.Amount))
        assertFalse(PayInFormConfiguration().isRequired(PayInField.Amount))
    }

    // --- formatting ---

    @Test
    fun `an expiry separator of nothing is refused where it is written`() {
        val failed =
            try {
                PayInFormatting(expirySeparator = "")
                false
            } catch (expected: IllegalArgumentException) {
                true
            }
        assertTrue("an empty separator runs the month into the year", failed)
    }

    @Test
    fun `formatting defaults to grouping the card number and masking the account`() {
        val formatting = PayInFormatting()
        assertTrue(formatting.groupsCardNumber)
        assertTrue(formatting.masksAccountNumber)
        assertEquals("/", formatting.expirySeparator)
    }

    @Test
    fun `the card brand mark is hidden until this module ships artwork for it`() {
        assertEquals(PayInCardBrandPlacement.Hidden, PayInFormConfiguration().cardBrandPlacement)
    }
}
