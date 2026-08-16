package com.payabli.sdk.taptopay.network

import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.provider.CardReadResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * What the two MoneyIn routes are sent, field by field.
 *
 * Encoding only, with no transport in it. What a body looks like on the wire is a separate question from
 * how a call is classified, and the sibling SDK keeps the same split for the same reason: these are the
 * assertions that catch a field quietly changing shape.
 */
class TTPTransactionWireFormatTest {
    private fun encode(body: InitiateBody): String = PayabliJson.format.encodeToString(InitiateBody.serializer(), body)

    private fun customerJson(customer: TapToPayCustomerData): String =
        PayabliJson.format.encodeToString(InitiateCustomerDataBody.serializer(), customer.toBody())

    private fun body(
        details: TapToPayPaymentDetails = TapToPayPaymentDetails(BigDecimal("10")),
        customer: TapToPayCustomerData = TapToPayCustomerData(),
        invoice: TapToPayInvoiceData = TapToPayInvoiceData(),
        orderDescription: String = "",
    ): InitiateBody =
        InitiateBody(
            entryPoint = "merchant-entry",
            orderDescription = orderDescription,
            paymentDetails = details.toBody(),
            paymentMethod = InitiatePaymentMethodBody(PAYMENT_METHOD_DEVICE, "poi-1"),
            customerData = customer.toBody(),
            invoiceData = invoice.toBody(),
        )

    @Test
    fun `the three legacy customer fields are sent empty rather than omitted`() {
        val json = customerJson(TapToPayCustomerData())

        assertEquals("""{"firstName":"","lastName":"","customerNumber":""}""", json)
    }

    @Test
    fun `every other customer field is omitted when it was not named`() {
        val json = customerJson(TapToPayCustomerData(billingCity = "Tampa"))

        assertTrue(json, json.contains(""""billingCity":"Tampa""""))
        assertFalse(json, json.contains("billingState"))
        assertFalse(json, json.contains("shippingCountry"))
        assertFalse(json, json.contains("company"))
    }

    @Test
    fun `a customer field holding only whitespace is not a value`() {
        val json = customerJson(TapToPayCustomerData(firstName = "  ", company = "\t"))

        assertTrue(json, json.contains(""""firstName":""""))
        assertFalse(json, json.contains("company"))
    }

    @Test
    fun `the customer identifier is a number and not a string`() {
        val json = customerJson(TapToPayCustomerData(customerId = 7))

        assertTrue(json, json.contains(""""customerId":7"""))
        assertFalse(json, json.contains(""""customerId":"7""""))
    }

    @Test
    fun `an order identifier never reaches this wire`() {
        val json = encode(body(orderDescription = "Table 4"))

        // The service reads `orderId` on the card-not-present routes and this module has never sent one.
        // Asserted rather than assumed, because adding it would silently start creating orders.
        assertFalse(json, json.contains("orderId"))
        assertTrue(json, json.contains(""""orderDescription":"Table 4""""))
    }

    @Test
    fun `an invoice is sent only when one was named`() {
        assertFalse(encode(body()).contains("invoiceData"))

        val named = encode(body(invoice = TapToPayInvoiceData("INV-9")))
        assertTrue(named, named.contains(""""invoiceData":{"invoiceNumber":"INV-9"}"""))
    }

    @Test
    fun `the payment method is the device flavour`() {
        val json = encode(body())

        assertTrue(json, json.contains(""""paymentMethod":{"method":"device","device":"poi-1"}"""))
    }

    @Test
    fun `amounts are written with two decimal places`() {
        val json = encode(body(TapToPayPaymentDetails(BigDecimal("10"), serviceFee = BigDecimal("0.5"))))

        assertTrue(json, json.contains(""""totalAmount":10.00"""))
        assertTrue(json, json.contains(""""serviceFee":0.50"""))
    }

    @Test
    fun `an amount is rounded to the scale it is sent at`() {
        val json = encode(body(TapToPayPaymentDetails(BigDecimal("10.005"))))

        assertTrue(json, json.contains(""""totalAmount":10.01"""))
    }

    @Test
    fun `the service fee is always written`() {
        val json = encode(body())

        assertTrue(json, json.contains(""""serviceFee":0.00"""))
    }

    @Test
    fun `a currency is upper-cased and a blank one is omitted`() {
        val named = encode(body(TapToPayPaymentDetails(BigDecimal("1"), currency = "usd")))
        assertTrue(named, named.contains(""""currency":"USD""""))

        val blank = encode(body(TapToPayPaymentDetails(BigDecimal("1"), currency = "   ")))
        assertFalse(blank, blank.contains("currency"))
    }

    @Test
    fun `the processor's response travels verbatim under the vendor-named key`() {
        val response = """{"gatewayResponse":{"transactionState":"CAPTURED"},"source":{"card":{"last4":"1111"}}}"""

        val body = updateSuccessBody(CardReadResult(cardNetwork = "Visa", providerResponse = response))

        assertEquals(setOf("fiservResponse"), body.keys)
        assertEquals(
            PayabliJson.format.parseToJsonElement(response),
            body["fiservResponse"],
        )
    }

    @Test
    fun `a reader answering with something that is not a JSON object is refused here`() {
        val notAnObject = CardReadResult(cardNetwork = null, providerResponse = "\"captured\"")

        val failure = runCatching { updateSuccessBody(notAnObject) }.exceptionOrNull()

        assertTrue("$failure", failure is SerializationException)
    }

    @Test
    fun `the failure report carries the fixed reason the service files it under`() {
        val json =
            PayabliJson.format.encodeToString(
                UpdateFailureBody.serializer(),
                UpdateFailureBody(UpdateErrorDetail(NFC_FAILURE_TITLE, "the card moved away", NFC_FAILURE_REASON)),
            )

        assertEquals(
            """{"error":{"title":"NFC Tap Failed","description":"the card moved away","failureReason":"nfc_read"}}""",
            json,
        )
    }

    @Test
    fun `the identifier is percent-encoded into the path and the template holds none of it`() {
        assertEquals("/api/v2/MoneyIn/update/12-abc", TTPRoutes.update("12-abc"))
        assertEquals("/api/v2/MoneyIn/update/a%2Fb%3Fc%23d", TTPRoutes.update("a/b?c#d"))
        assertEquals("/api/v2/MoneyIn/update/%20", TTPRoutes.update(" "))
        assertFalse(TTPRoutes.UPDATE, TTPRoutes.UPDATE.contains("12-abc"))
    }

    @Test
    fun `the opening response is read for one field and tolerates the casing the service varies`() {
        listOf("paymentTransId", "paymenttransid", "PaymentTransId").forEach { key ->
            val payload =
                PayabliJson.format.decodeFromString(
                    InitiatePayload.serializer(),
                    """{"$key":"12-abc","totalAmount":10.00}""",
                )
            assertEquals(key, "12-abc", payload.paymentTransId)
        }
    }

    @Test
    fun `an amount reads whether or not it arrives quoted`() {
        // The descriptor declares a string and the writer emits a number, so both forms have to read or the
        // serializer cannot round-trip its own output.
        assertEquals(BigDecimal("10.00"), PayabliJson.format.decodeFromString(TTPAmountSerializer, "\"10.00\""))
        assertEquals(BigDecimal("10.00"), PayabliJson.format.decodeFromString(TTPAmountSerializer, "10.00"))
    }

    @Test
    fun `a value that is not a number is a decode failure rather than a zero`() {
        listOf("\"ten\"", "true", "{}").forEach { body ->
            val failure =
                runCatching { PayabliJson.format.decodeFromString(TTPAmountSerializer, body) }.exceptionOrNull()

            assertTrue("$body gave $failure", failure is SerializationException)
        }
    }

    @Test
    fun `nothing that identifies a payer or a payment survives a toString`() {
        val customer = TapToPayCustomerData(firstName = "Ada", billingEmail = "ada@example.com")
        val details = TapToPayPaymentDetails(BigDecimal("10"), currency = "USD")
        val read = CardReadResult(cardNetwork = "Visa", providerResponse = """{"source":{"card":{"last4":"1111"}}}""")

        listOf(
            customer.toString(),
            details.toString(),
            customer.toBody().toString(),
            details.toBody().toString(),
            body(details, customer).toString(),
            read.toString(),
        ).forEach { rendered ->
            listOf("Ada", "ada@example.com", "10", "1111", "last4").forEach { secret ->
                assertFalse("$rendered leaked $secret", rendered.contains(secret))
            }
        }
    }

    @Test
    fun `an empty body is not what an update sends`() {
        val body: JsonObject = updateSuccessBody(CardReadResult(null, "{}"))

        assertNull(body["error"])
        assertEquals(JsonObject(emptyMap()), body["fiservResponse"])
    }
}
