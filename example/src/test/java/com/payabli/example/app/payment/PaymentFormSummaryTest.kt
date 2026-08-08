package com.payabli.example.app.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests exist for one property: the readout is derived, not transcribed.
 *
 * A hand-written list would pass a test that compares it against itself. Each test below instead
 * changes the configuration and asserts the readout followed, which is the only way to catch the
 * failure this section is meant to prevent — a screen that describes a form it no longer matches.
 */
class PaymentFormSummaryTest {
    private fun rowsOf(configuration: PaymentFormConfiguration) =
        PaymentFormSummary.rows(configuration).associate { it.label to it.value }

    @Test
    fun `the readout names every card field the form actually renders, in order`() {
        val configuration = PaymentFormConfiguration.storePaymentMethod()
        val expected =
            configuration
                .sectionsFor(PaymentMethodType.Card)
                .flatMap { it.fields }
                .joinToString(", ") { it.fieldName }
        assertEquals(expected, rowsOf(configuration)["Card fields"])
    }

    @Test
    fun `the readout names every bank field the form actually renders, in order`() {
        val configuration = PaymentFormConfiguration.storePaymentMethod()
        val expected =
            configuration
                .sectionsFor(PaymentMethodType.BankAccount)
                .flatMap { it.fields }
                .joinToString(", ") { it.fieldName }
        assertEquals(expected, rowsOf(configuration)["Bank account fields"])
    }

    @Test
    fun `adding a field to a section adds it to the readout`() {
        // The drift case, made to happen. A transcribed list would not notice.
        val base = PaymentFormConfiguration.storePaymentMethod()
        val widened =
            base.copy(
                cardSections =
                    base.cardSections.map { section ->
                        if (section.title == "Card") {
                            section.copy(fields = section.fields + PaymentField.RoutingNumber)
                        } else {
                            section
                        }
                    },
            )
        assertTrue(rowsOf(widened)["Card fields"]!!.contains(PaymentField.RoutingNumber.fieldName))
        assertTrue(!rowsOf(base)["Card fields"]!!.contains(PaymentField.RoutingNumber.fieldName))
    }

    @Test
    fun `changing the allowed methods changes the readout`() {
        val base = PaymentFormConfiguration.storePaymentMethod()
        assertEquals("card, bank account", rowsOf(base)["Allowed methods"])

        val cardOnly = base.copy(allowedMethods = listOf(PaymentMethodType.Card))
        assertEquals("card", rowsOf(cardOnly)["Allowed methods"])
    }

    @Test
    fun `changing the default method changes the readout`() {
        val base = PaymentFormConfiguration.storePaymentMethod()
        assertEquals("card", rowsOf(base)["Default method"])
        assertEquals(
            "bank account",
            rowsOf(base.copy(defaultMethod = PaymentMethodType.BankAccount))["Default method"],
        )
    }

    @Test
    fun `capture's amount fields are not listed as form fields, because nobody types them`() {
        val capture = rowsOf(PaymentFormConfiguration.capture())
        assertTrue(!capture["Card fields"]!!.contains(PaymentField.Amount.fieldName))
        assertTrue(!capture["Card fields"]!!.contains(PaymentField.ServiceFee.fieldName))
    }

    @Test
    fun `capture and store list the same typed fields, because only the summary section differs`() {
        assertEquals(
            rowsOf(PaymentFormConfiguration.storePaymentMethod())["Card fields"],
            rowsOf(PaymentFormConfiguration.capture())["Card fields"],
        )
    }

    @Test
    fun `the masked row names exactly the fields the form hides`() {
        val configuration = PaymentFormConfiguration.capture()
        val masked = rowsOf(configuration)["Masked"]!!
        configuration.allowedMethods
            .flatMap { configuration.sectionsFor(it) }
            .flatMap { it.fields }
            .distinct()
            .forEach { field ->
                val named = masked.contains(field.fieldName)
                assertEquals(
                    "${field.name} is ${if (field.input == FieldInput.Secret) "secret" else "not secret"} but the readout says otherwise",
                    field.input == FieldInput.Secret,
                    named,
                )
            }
    }

    @Test
    fun `a method the form will not offer is not described`() {
        // The readout is what a reader checks the form against, so an inputs list for a method no
        // payer can select reads as fields that are there and are not.
        val cardOnly =
            PaymentFormConfiguration.storePaymentMethod().copy(
                allowedMethods = listOf(PaymentMethodType.Card),
                defaultMethod = PaymentMethodType.Card,
            )
        val rows = rowsOf(cardOnly)
        assertTrue(rows.containsKey("Card fields"))
        assertTrue(!rows.containsKey("Bank account fields"))
    }

    @Test
    fun `masked names no field the form does not render`() {
        val cardOnly =
            PaymentFormConfiguration.storePaymentMethod().copy(
                allowedMethods = listOf(PaymentMethodType.Card),
                defaultMethod = PaymentMethodType.Card,
            )
        val bankOnlySecrets =
            PaymentField.entries.filter { field ->
                field.input == FieldInput.Secret &&
                    cardOnly.sectionsFor(PaymentMethodType.Card).flatMap { it.fields }.none { it == field }
            }
        // Without this the test passes on an empty list, which reads the same as passing on the
        // property it is here for.
        assertTrue("no secret field is bank-only, so this proves nothing", bankOnlySecrets.isNotEmpty())
        val masked = rowsOf(cardOnly)["Masked"]!!
        bankOnlySecrets.forEach { assertTrue("${it.name} is named", !masked.contains(it.fieldName)) }
    }

    @Test
    fun `every row has a label and a value`() {
        PaymentFormSummary.rows(PaymentFormConfiguration.capture()).forEach { row ->
            assertTrue("a row has no label", row.label.isNotBlank())
            assertTrue("${row.label} has no value", row.value.isNotBlank())
        }
    }
}
