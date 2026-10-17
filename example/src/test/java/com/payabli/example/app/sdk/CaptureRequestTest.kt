package com.payabli.example.app.sdk

import com.payabli.example.app.demo.sample.SampleAmount
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.sdk.core.model.PayabliAttempt
import com.payabli.sdk.payin.model.PayInCustomerData
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.payin.model.PayInValidationOptions
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import kotlin.random.Random

/** What a capture actually sends, against what the screen showed and against who it says is paying. */
class CaptureRequestTest {
    private val identity = SampleIdentity.from("Google Pixel 7a")

    @Test
    fun `the request charges the amount it was built from`() {
        val random = Random(seed = 3)

        repeat(500) {
            val total = SampleAmount.random(random)

            assertEquals(
                "the request does not charge the amount it was given",
                total,
                sent(total).paymentDetails.totalAmount,
            )
        }
    }

    /**
     * Every payment from one device names one customer.
     *
     * The capture form collects no customer number, so with none supplied here and `forceCustomerCreation` set
     * the paypoint has nothing to match on and writes a new customer per payment. Measured against a live paypoint: three captures
     * from one device produced three customers, each with no number at all.
     */
    @Test
    fun `a capture identifies the customer it is for`() {
        val options = sent(BigDecimal("2.50"), suppliesDemoCustomer = true)

        assertEquals(identity.customerNumber, options.customerData?.customerNumber)
        assertEquals(true, options.forceCustomerCreation)
    }

    @Test
    fun `the switch off sends no customer number`() {
        // The number and not the customer: the capture form collects a first name, a last name and a billing
        // email, and the SDK writes those into the body over whatever this configures, so a request with none
        // configured still names a payer. What the switch decides is whether the paypoint has a number to
        // match on, which is what stops it writing a fresh customer per payment.
        assertEquals(null, sent(BigDecimal("2.50"), suppliesDemoCustomer = false).customerData?.customerNumber)
    }

    /**
     * The device is on the request, not only in the values the identity can produce.
     *
     * `SampleIdentityTest` covers what an order identifier and a note look like. What it cannot cover is whether
     * either reaches `PayInTransactionOptions`, and those two fields are the whole of what a dashboard reads to
     * say which device sent a payment: dropping either assignment loses attribution and changes no test.
     */
    @Test
    fun `a capture carries the device in the fields a transaction list shows`() {
        val options = sent(BigDecimal("2.50"))

        assertEquals(identity.orderId(AT_MILLIS), options.orderId)
        assertEquals(identity.note("capture"), options.orderDescription)
    }

    /** What the request would carry for [total], read back off the operation the screen submits. */
    private fun sent(
        total: BigDecimal,
        suppliesDemoCustomer: Boolean = true,
    ): PayInTransactionOptions =
        (
            capturePayment(
                paymentReference = "ref",
                amount = total,
                identity = identity,
                atMillis = AT_MILLIS,
                suppliesDemoCustomer = suppliesDemoCustomer,
            ).operation as PayabliPayInOperation.Capture
        ).options

    private companion object {
        /** Any fixed moment. The order identifier is built from it, so the assertion needs the same one. */
        const val AT_MILLIS = 1_776_000_000_000L
    }

    @Test
    fun `a retry repeats every field and declares the retry`() {
        // A field that changed between the two would make the retry a different payment from the one it repeats.
        val customer = PayInCustomerData(customerNumber = "c-1")
        val validation = PayInValidationOptions(checksCardNumber = false)
        val original =
            PayInTransactionOptions(
                paymentDetails = PayInPaymentDetails(totalAmount = BigDecimal("2.50")),
                customerData = customer,
                accountId = "a",
                ipAddress = "ip",
                orderId = "o",
                orderDescription = "d",
                source = "s",
                subdomain = "sub",
                subscriptionId = 7L,
                achValidation = true,
                forceCustomerCreation = false,
                validation = validation,
                paymentReference = "ref-1",
            )

        val retry =
            (
                PayInOperation(
                    PayabliPayInOperation.Capture(original),
                ).asRetry().operation as PayabliPayInOperation.Capture
            ).options

        assertEquals(PayabliAttempt.Retry, retry.attempt)
        assertEquals(
            listOf<Any?>(
                original.paymentDetails,
                customer,
                "a",
                "ip",
                "o",
                "d",
                "s",
                "sub",
                7L,
                true,
                false,
                validation,
                "ref-1",
            ),
            listOf<Any?>(
                retry.paymentDetails,
                retry.customerData,
                retry.accountId,
                retry.ipAddress,
                retry.orderId,
                retry.orderDescription,
                retry.source,
                retry.subdomain,
                retry.subscriptionId,
                retry.achValidation,
                retry.forceCustomerCreation,
                retry.validation,
                retry.paymentReference,
            ),
        )
    }
}
