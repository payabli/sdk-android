package com.payabli.example.app.payment

import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldInput
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormSection
import com.payabli.sdk.payin.form.PayInFormatting
import com.payabli.sdk.payin.form.PayInMethodType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests exist for one property: the readout is derived, not transcribed.
 *
 * A hand-written list would pass a test that compares it against itself. Each test below instead
 * changes the configuration and asserts the readout followed, which is the only way to catch the
 * failure this section is meant to prevent — a screen that describes a form it no longer matches.
 *
 * The configuration is the SDK's own type now, so these also read as a check on it: a form nobody
 * can describe from its configuration is one an integrator cannot reason about either.
 */
class PaymentFormSummaryTest {
    private fun rowsOf(configuration: PayInFormConfiguration) =
        PaymentFormSummary.rows(configuration).associate { it.label to it.value }

    private val storeMethod get() = DemoForms.storePaymentMethod().configuration

    @Test
    fun `the readout names every card field the form actually renders, in order`() {
        val expected = storeMethod.inputFieldsFor(PayInMethodType.Card).joinToString(", ") { it.fieldName }
        assertEquals(expected, rowsOf(storeMethod)["Card fields"])
    }

    @Test
    fun `the readout names every bank field the form actually renders, in order`() {
        val expected = storeMethod.inputFieldsFor(PayInMethodType.BankAccount).joinToString(", ") { it.fieldName }
        assertEquals(expected, rowsOf(storeMethod)["Bank account fields"])
    }

    @Test
    fun `adding a field to a section adds it to the readout`() {
        // The drift case, made to happen. A transcribed list would not notice.
        val widened =
            storeMethod.copy(
                cardSections =
                    storeMethod.cardSections.map { section ->
                        if (section.title == "Card Information") {
                            section.copy(fields = section.fields + PayInField.RoutingNumber)
                        } else {
                            section
                        }
                    },
            )

        assertTrue(rowsOf(widened)["Card fields"]!!.contains(PayInField.RoutingNumber.fieldName))
        assertTrue(!rowsOf(storeMethod)["Card fields"]!!.contains(PayInField.RoutingNumber.fieldName))
    }

    @Test
    fun `changing the allowed methods changes the readout`() {
        assertEquals("card, bank account", rowsOf(storeMethod)["Allowed methods"])

        val cardOnly = storeMethod.copy(allowedMethods = listOf(PayInMethodType.Card))
        assertEquals("card", rowsOf(cardOnly)["Allowed methods"])
    }

    @Test
    fun `changing the default method changes the readout`() {
        assertEquals("card", rowsOf(storeMethod)["Default method"])
        assertEquals(
            "bank account",
            rowsOf(storeMethod.copy(defaultMethod = PayInMethodType.BankAccount))["Default method"],
        )
    }

    @Test
    fun `capture's amount fields are not listed as form fields, because nobody types them`() {
        val capture = rowsOf(DemoForms.capture().configuration)
        assertTrue(!capture["Card fields"]!!.contains(PayInField.Amount.fieldName))
        assertTrue(!capture["Card fields"]!!.contains(PayInField.ServiceFee.fieldName))
    }

    @Test
    fun `store asks for a customer number and capture does not, and nothing else differs`() {
        // A stored method belongs to a customer and the service refuses one it cannot identify, so that field
        // is on the store form only. Everything else the two collect is the same.
        val store = storeMethod.inputFieldsFor(PayInMethodType.Card)
        val capture = DemoForms.capture().configuration.inputFieldsFor(PayInMethodType.Card)

        assertEquals(listOf(PayInField.CustomerNumber), store - capture.toSet())
        assertEquals(emptyList<PayInField>(), capture - store.toSet())
        assertTrue(rowsOf(storeMethod)["Card fields"]!!.contains(PayInField.CustomerNumber.fieldName))
    }

    @Test
    fun `the masked row names exactly the fields the form hides`() {
        val configuration = DemoForms.capture().configuration
        val masked = rowsOf(configuration)["Masked"]!!
        configuration.methodsOffered
            .flatMap { configuration.inputFieldsFor(it) }
            .distinct()
            .forEach { field ->
                assertEquals(
                    "${field.name}: the readout disagrees with configuration.masks",
                    configuration.masks(field),
                    masked.contains(field.fieldName),
                )
            }
    }

    @Test
    fun `turning off account masking takes the account number out of the readout`() {
        // The readout is what a reader checks the form against, so naming a field as masked while
        // the form shows it in clear text is the one thing it must not do.
        val open =
            DemoForms.capture().configuration.copy(
                formatting = PayInFormatting(masksAccountNumber = false),
            )
        val masked = rowsOf(open)["Masked"]!!

        assertTrue("the account number is still named", !masked.contains(PayInField.AccountNumber.fieldName))
        assertTrue("the security code stopped being named", masked.contains(PayInField.CardSecurityCode.fieldName))
    }

    @Test
    fun `a method the form will not offer is not described`() {
        // The readout is what a reader checks the form against, so an inputs list for a method no
        // payer can select reads as fields that are there and are not.
        val cardOnly =
            storeMethod.copy(
                allowedMethods = listOf(PayInMethodType.Card),
                defaultMethod = PayInMethodType.Card,
            )
        val rows = rowsOf(cardOnly)

        assertTrue(rows.containsKey("Card fields"))
        assertTrue(!rows.containsKey("Bank account fields"))
    }

    @Test
    fun `masked names no field the form does not render`() {
        val cardOnly =
            storeMethod.copy(
                allowedMethods = listOf(PayInMethodType.Card),
                defaultMethod = PayInMethodType.Card,
            )
        val cardFields = cardOnly.inputFieldsFor(PayInMethodType.Card)
        val bankOnlySecrets =
            PayInField.entries.filter { it.input == PayInFieldInput.Secret && it !in cardFields }

        // Without this the test passes on an empty list, which reads the same as passing on the
        // property it is here for.
        assertTrue("no secret field is bank-only, so this proves nothing", bankOnlySecrets.isNotEmpty())
        val masked = rowsOf(cardOnly)["Masked"]!!
        bankOnlySecrets.forEach { assertTrue("${it.name} is named", !masked.contains(it.fieldName)) }
    }

    @Test
    fun `every row has a label and a value`() {
        PaymentFormSummary.rows(DemoForms.capture().configuration).forEach { row ->
            assertTrue("a row has no label", row.label.isNotBlank())
            assertTrue("${row.label} has no value", row.value.isNotBlank())
        }
    }

    @Test
    fun `the sections this app configures survive the SDK's own normalising`() {
        // The app writes sections and the SDK de-duplicates and drops empties. A field this app asks
        // for and the SDK removes would leave the readout describing a form it does not render.
        listOf(DemoForms.storePaymentMethod(), DemoForms.capture()).forEach { setup ->
            val configured =
                setup.configuration.cardSections
                    .filter { it.style == com.payabli.sdk.payin.form.PayInSectionStyle.Inputs }
                    .flatMap { it.fields }
            assertEquals(configured, setup.configuration.inputFieldsFor(PayInMethodType.Card))
        }
    }

    @Test
    fun `a section this app titles keeps that title`() {
        val titles =
            DemoForms
                .capture()
                .configuration.cardSections
                .mapNotNull(PayInFormSection::title)
        assertEquals(listOf("Card Information", "Customer Information", "Payment Information"), titles)
    }
}
