package com.payabli.sdk.payin.client

import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.model.PayInCustomerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the payer typed has to reach the request.
 *
 * The form collects a customer and a description for the stored method, and neither is part of the instrument,
 * so nothing else carries them. Dropped, a capture goes out with no customer at all and the paypoint answers
 * `400 Error in customer data`, which is what these exist to prevent.
 */
class PayInEnteredDetailsTest {
    private fun values(vararg entries: Pair<PayInField, String>) =
        PayInFormValues(PayInMethodType.Card, mapOf(*entries))

    @Test
    fun `every customer field the form collects is read`() {
        val entered =
            PayInEnteredDetails.of(
                values(
                    PayInField.FirstName to "Ada",
                    PayInField.LastName to "Lovelace",
                    PayInField.CustomerNumber to "cust-9",
                    PayInField.BillingEmail to "ada@example.test",
                    PayInField.BillingPostalCode to "90001",
                    PayInField.MethodDescription to "on file",
                ),
            )

        assertEquals("Ada", entered.firstName)
        assertEquals("Lovelace", entered.lastName)
        assertEquals("cust-9", entered.customerNumber)
        assertEquals("ada@example.test", entered.billingEmail)
        assertEquals("90001", entered.billingZip)
        assertEquals("on file", entered.methodDescription)
    }

    @Test
    fun `a field the form did not collect, and one left blank, are both absent`() {
        // Absent rather than empty: the service reads an empty string as a value, and a payer who typed
        // nothing named nothing.
        val entered = PayInEnteredDetails.of(values(PayInField.FirstName to "   "))

        assertNull(entered.firstName)
        assertNull(entered.lastName)
        assertNull(entered.billingEmail)
    }

    @Test
    fun `surrounding space is not part of what was typed`() {
        val entered = PayInEnteredDetails.of(values(PayInField.BillingEmail to "  ada@example.test  "))

        assertEquals("ada@example.test", entered.billingEmail)
    }

    @Test
    fun `the typed customer is what the body carries when nothing was configured`() {
        val body = null.toBody(PayInEnteredDetails(firstName = "Ada", billingEmail = "ada@example.test"))

        assertEquals("Ada", body?.firstName)
        assertEquals("ada@example.test", body?.billingEmail)
    }

    @Test
    fun `a configured field the form does not collect survives`() {
        // A host knows things the form never asks for, and the merge fills the configured customer rather
        // than standing in for it: a typed name leaves the configured address alone.
        val configured =
            PayInCustomerData(
                billingAddress1 = "1 Test Street",
                billingCity = "Springfield",
                billingCountry = "US",
                additionalData = mapOf("plan" to "gold"),
            )

        val body = configured.toBody(PayInEnteredDetails(firstName = "Ada"))

        assertEquals("Ada", body?.firstName)
        assertEquals("1 Test Street", body?.billingAddress1)
        assertEquals("Springfield", body?.billingCity)
        assertEquals("US", body?.billingCountry)
        assertEquals(mapOf("plan" to "gold"), body?.additionalData)
    }

    @Test
    fun `the typed value wins over the configured one for the field it names`() {
        // The payer edited the box after the host configured it, so theirs is the later of the two.
        val configured = PayInCustomerData(firstName = "Configured", billingEmail = "configured@example.test")

        val body = configured.toBody(PayInEnteredDetails(firstName = "Ada"))

        assertEquals("Ada", body?.firstName)
        assertEquals("configured@example.test", body?.billingEmail)
    }

    @Test
    fun `an optional box cleared by the payer leaves the configured value standing`() {
        // The customer number and the method description are the only two fields that can be collected and
        // left empty. The form refuses to submit the other four.
        val configured = PayInCustomerData(customerNumber = "host-4471", firstName = "Ada", lastName = "Lovelace")
        val cleared =
            PayInEnteredDetails.of(
                PayInFormValues(
                    PayInMethodType.Card,
                    mapOf(
                        PayInField.FirstName to "Ada",
                        PayInField.LastName to "Lovelace",
                        PayInField.CustomerNumber to "   ",
                    ),
                ),
            )

        assertEquals("host-4471", configured.toBody(cleared)?.customerNumber)
    }

    @Test
    fun `a configured customer with nothing typed is carried as it stands`() {
        val configured = PayInCustomerData(firstName = "Configured", customerId = 77L)

        val body = configured.toBody(PayInEnteredDetails.NONE)

        assertEquals("Configured", body?.firstName)
        assertEquals(77L, body?.customerId)
    }

    @Test
    fun `neither side naming anything is no customer, rather than an empty one`() {
        // A present customerData is a customer for the service to act on, and `{}` is a different request from
        // none: it can create an anonymous customer on a paypoint configured for it.
        assertNull(null.toBody(PayInEnteredDetails.NONE))
        assertNull(PayInCustomerData().toBody(PayInEnteredDetails.NONE))
    }

    @Test
    fun `the body a customer becomes renders none of it`() {
        // It reaches a crash reporter through any object that holds it, and every field is personal data.
        val rendered =
            PayInCustomerData(firstName = "Ada", billingEmail = "ada@example.test", billingZip = "90001")
                .toBody(PayInEnteredDetails(lastName = "Lovelace"))
                .toString()

        listOf("Ada", "Lovelace", "ada@example.test", "90001").forEach {
            assertEquals("$it was rendered", false, rendered.contains(it))
        }
    }
}
