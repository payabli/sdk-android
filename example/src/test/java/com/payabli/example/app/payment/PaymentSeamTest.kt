package com.payabli.example.app.payment

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentSeamTest {
    // --- configuration ---

    @Test
    fun `storing a method asks for no amount, because nothing is being charged`() {
        val configuration = PaymentFormConfiguration.storePaymentMethod()
        val allFields =
            (configuration.cardSections + configuration.bankSections).flatMap { it.fields }
        assertFalse(allFields.contains(PaymentField.Amount))
        assertFalse(allFields.contains(PaymentField.ServiceFee))
    }

    @Test
    fun `capture asks for an amount on both instruments`() {
        val configuration = PaymentFormConfiguration.capture()
        listOf(PaymentMethodType.Card, PaymentMethodType.BankAccount).forEach { method ->
            val fields = configuration.sectionsFor(method).flatMap { it.fields }
            assertTrue("$method has no amount", fields.contains(PaymentField.Amount))
        }
    }

    @Test
    fun `the default method is one of the allowed ones`() {
        listOf(PaymentFormConfiguration.storePaymentMethod(), PaymentFormConfiguration.capture())
            .forEach { assertTrue(it.allowedMethods.contains(it.defaultMethod)) }
    }

    @Test
    fun `sectionsFor returns the instrument's own sections`() {
        val configuration = PaymentFormConfiguration.capture()
        val card = configuration.sectionsFor(PaymentMethodType.Card).flatMap { it.fields }
        val bank = configuration.sectionsFor(PaymentMethodType.BankAccount).flatMap { it.fields }
        assertTrue(card.contains(PaymentField.CardNumber))
        assertFalse(card.contains(PaymentField.RoutingNumber))
        assertTrue(bank.contains(PaymentField.RoutingNumber))
        assertFalse(bank.contains(PaymentField.CardNumber))
    }

    @Test
    fun `no section is empty and every section has a title`() {
        listOf(PaymentFormConfiguration.storePaymentMethod(), PaymentFormConfiguration.capture())
            .flatMap { it.cardSections + it.bankSections }
            .forEach { section ->
                assertTrue("a section has no title", section.title.isNotBlank())
                assertTrue("${section.title} has no fields", section.fields.isNotEmpty())
            }
    }

    @Test
    fun `the account number is obscured as it is typed`() {
        assertEquals(FieldInput.Secret, PaymentField.AccountNumber.input)
    }

    @Test
    fun `every field has a label, and no two share one`() {
        // Uniqueness. Some correct labels are single words that match the enum name, and two fields
        // sharing a label on one form is the failure worth catching.
        PaymentField.entries.forEach { field ->
            assertTrue("${field.name} has no label", field.label.isNotBlank())
        }
        val labels = PaymentField.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `no label is camelCase`() {
        val camelCase = Regex(".*[a-z][A-Z].*")
        PaymentField.entries.forEach { field ->
            assertFalse("${field.name} label is camelCase", camelCase.matches(field.label))
        }
    }

    // --- errors ---

    @Test
    fun `a detail that adds something gets its own line`() {
        val error = PaymentError.Payabli("Declined", "Insufficient funds")
        assertEquals("Declined\nInsufficient funds", error.displayMessage)
    }

    @Test
    fun `a null detail leaves the reason alone`() {
        assertEquals("Declined", PaymentError.Payabli("Declined", null).displayMessage)
    }

    @Test
    fun `an empty detail leaves the reason alone`() {
        assertEquals("Declined", PaymentError.Payabli("Declined", "").displayMessage)
    }

    @Test
    fun `a detail identical to the reason is not printed twice`() {
        assertEquals("Declined", PaymentError.Payabli("Declined", "Declined").displayMessage)
    }

    @Test
    fun `an unexpected error shows its own text`() {
        assertEquals("Something broke", PaymentError.Unexpected("Something broke").displayMessage)
    }

    // --- transaction summary ---

    private fun fullResult() =
        PaymentResult(
            code = "1",
            reason = "Approved",
            explanation = "Authorised and captured.",
            action = "None",
            transaction =
                Transaction(
                    paymentTransactionId = "txn-1",
                    gatewayTransactionId = "gw-1",
                    orderId = "order-1",
                    method = "card",
                    operation = "capture",
                    status = "Captured",
                    totalAmount = "1.10",
                    feeAmount = "0.10",
                    source = "android-example",
                ),
        )

    @Test
    fun `the summary is thirteen rows in a fixed order`() {
        val rows = TransactionSummary.rows(fullResult())
        assertEquals(13, rows.size)
        assertEquals("Code", rows.first().label)
        assertEquals("Source", rows.last().label)
    }

    @Test
    fun `every row is present even when the transaction is absent`() {
        val rows = TransactionSummary.rows(PaymentResult(code = "1"))
        assertEquals(13, rows.size)
        assertTrue(rows.drop(1).all { it.value == "—" })
    }

    @Test
    fun `amounts are formatted as dollars`() {
        val rows = TransactionSummary.rows(fullResult()).associate { it.label to it.value }
        assertEquals("$ 1.10", rows["Total"])
        assertEquals("$ 0.10", rows["Fee"])
    }

    @Test
    fun `an amount formats the same whatever the device locale is set to`() {
        // A comma-decimal locale must not turn 1.00 into "1,00", which reads as a different number.
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("$ 1.10", TransactionSummary.formatAmount("1.10"))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun `a wide amount keeps every digit`() {
        // Double holds about 15 significant digits, so this value loses the cents before it is ever
        // displayed. It is the case that makes BigDecimal the right type rather than a preference.
        assertEquals("$ 12345678901234567.89", TransactionSummary.formatAmount("12345678901234567.89"))
    }

    @Test
    fun `a fraction binary floating point cannot hold is not disturbed`() {
        assertEquals("$ 1.10", TransactionSummary.formatAmount("1.10"))
        assertEquals("$ 0.07", TransactionSummary.formatAmount("0.07"))
    }

    @Test
    fun `an amount that will not parse is shown as it arrived`() {
        assertEquals("about a tenner", TransactionSummary.formatAmount("about a tenner"))
    }

    @Test
    fun `a missing amount reads as missing`() {
        assertEquals("—", TransactionSummary.formatAmount(null))
        assertEquals("—", TransactionSummary.formatAmount("  "))
    }

    // --- response json ---

    @Test
    fun `keys are sorted`() {
        val rendered =
            ResponseJson.render(
                buildJsonObject {
                    put("zebra", JsonPrimitive(1))
                    put("apple", JsonPrimitive(2))
                },
            )
        assertTrue(rendered.indexOf("apple") < rendered.indexOf("zebra"))
    }

    @Test
    fun `nested objects are sorted too`() {
        val rendered =
            ResponseJson.render(
                buildJsonObject {
                    put(
                        "outer",
                        buildJsonObject {
                            put("zebra", JsonPrimitive(1))
                            put("apple", JsonPrimitive(2))
                        },
                    )
                },
            )
        assertTrue(rendered.indexOf("apple") < rendered.indexOf("zebra"))
    }

    @Test
    fun `array order is left alone, because it is data`() {
        val rendered =
            ResponseJson.render(
                buildJsonObject {
                    put(
                        "items",
                        buildJsonArray {
                            add(JsonPrimitive("zebra"))
                            add(JsonPrimitive("apple"))
                        },
                    )
                },
            )
        assertTrue(rendered.indexOf("zebra") < rendered.indexOf("apple"))
    }

    @Test
    fun `a null response says so`() {
        assertEquals(ResponseJson.UNRENDERABLE, ResponseJson.render(null))
    }

    // --- the demo controller ---

    @Test
    fun `storing a method returns a stored method and no transaction`() =
        runTest {
            val controller = DemoPaymentFlowController(PaymentOperation.StoreMethod, stepDelayMillis = 0)
            val result = controller.submit().getOrThrow()
            assertNotNull(result.storedMethod)
            assertNull(result.transaction)
        }

    @Test
    fun `capture returns a transaction and no stored method`() =
        runTest {
            val controller = DemoPaymentFlowController(PaymentOperation.Capture, stepDelayMillis = 0)
            val result = controller.submit().getOrThrow()
            assertNotNull(result.transaction)
            assertNull(result.storedMethod)
        }

    @Test
    fun `each submission gets its own identifier`() =
        runTest {
            val controller = DemoPaymentFlowController(PaymentOperation.Capture, stepDelayMillis = 0)
            val first =
                controller
                    .submit()
                    .getOrThrow()
                    .transaction
                    ?.paymentTransactionId
            val second =
                controller
                    .submit()
                    .getOrThrow()
                    .transaction
                    ?.paymentTransactionId
            assertEquals("demo-txn-0001", first)
            assertEquals("demo-txn-0002", second)
        }

    @Test
    fun `the controller's configuration matches its operation`() {
        assertEquals(
            PaymentFormConfiguration.capture(),
            DemoPaymentFlowController(PaymentOperation.Capture).configuration,
        )
        assertEquals(
            PaymentFormConfiguration.storePaymentMethod(),
            DemoPaymentFlowController(PaymentOperation.StoreMethod).configuration,
        )
    }

    @Test
    fun `the demo result renders through the summary and the json without special casing`() =
        runTest {
            val controller = DemoPaymentFlowController(PaymentOperation.Capture, stepDelayMillis = 0)
            val result = controller.submit().getOrThrow()
            assertEquals(13, TransactionSummary.rows(result).size)
            assertFalse(ResponseJson.render(result.apiResponse) == ResponseJson.UNRENDERABLE)
        }

    // The form's own fields, their validation and the debug prefill that filled them are the SDK
    // component's, and are not in this app. What stays covered here is the configuration handed to
    // it and everything it hands back.
}
