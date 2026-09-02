package com.payabli.example.app.sdk

import com.payabli.example.app.demo.payment.PaymentError
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInFailure
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.model.PayInStoredMethod
import com.payabli.sdk.payin.model.PayInTransaction
import com.payabli.sdk.payin.payment.PayInSubmissionState
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The one place an SDK outcome becomes this app's own types.
 *
 * Two properties, and the screens depend on both. Every field the screens read is filled from the outcome, so
 * a readout cannot show a value the service never sent; and no instrument crosses, because none is in what the
 * SDK returns.
 */
class PayInOutcomesTest {
    @Test
    fun `a capture keeps every identifier the transaction screen shows`() {
        val result = capturedPaymentOutcome().result
        val transaction = requireNotNull(result.transaction)

        assertEquals("A0000", result.code)
        assertEquals("101-abc", transaction.paymentTransactionId)
        assertEquals("gtw-9", transaction.gatewayTransactionId)
        assertEquals("order-1", transaction.orderId)
        assertEquals("card", transaction.method)
        assertEquals("fiserv", transaction.source)
        assertEquals("1", transaction.status)
    }

    @Test
    fun `amounts cross as the API sent them`() {
        // A payment amount reformatted on its way to a screen is how a display comes to disagree with the
        // response beside it, so these are the plain strings of what arrived.
        val transaction = requireNotNull(capturedPaymentOutcome().result.transaction)

        assertEquals("1.10", transaction.totalAmount)
        assertEquals("0.10", transaction.feeAmount)
    }

    @Test
    fun `the fee is the difference, and is absent when either half is`() {
        // Derived, because the service reports a total and a net. Without both there is no fee to state, and
        // showing zero would claim the paypoint took none.
        val netOnly = payment(totalAmount = null, netAmount = BigDecimal("1.00"))
        val totalOnly = payment(totalAmount = BigDecimal("1.10"), netAmount = null)

        assertNull(netOnly.toPaymentResult().transaction?.feeAmount)
        assertNull(totalOnly.toPaymentResult().transaction?.feeAmount)
    }

    @Test
    fun `a capture names the operation it was, because the SDK reports the transaction and not the call`() {
        assertEquals("capture", capturedPaymentOutcome().result.transaction?.operation)
    }

    @Test
    fun `an approval with no transaction leaves the transaction empty and still carries the code`() {
        // What the capture screen reads to call an approval a failure. A fabricated empty transaction here
        // would have it announce a captured payment against a response that named none.
        val result =
            PayInSubmissionState.Succeeded
                .Payment(
                    PayInResult("A0000", reason = null, explanation = null, action = null, transaction = null),
                ).toPaymentResult()

        assertNull(result.transaction)
        assertEquals("A0000", result.code)
    }

    @Test
    fun `a stored method keeps the identifier a later transaction charges`() {
        val result = storedMethodOutcome().result

        assertEquals("tok-77", result.storedMethod?.storedMethodId)
        assertEquals("Approved", result.storedMethod?.resultText)
        assertEquals("1", result.code)
        assertNull("a stored method is not a transaction", result.transaction)
    }

    @Test
    fun `a stored method with no result code still describes what was stored`() {
        // The identifier is what the screen exists to show, and the service can approve without a code.
        val outcome =
            PayInSubmissionState.Succeeded.Method(
                PayInStoredMethod(
                    storedMethodId = "tok-1",
                    methodReferenceId = null,
                    customerId = null,
                    resultCode = null,
                    resultText = null,
                ),
            )

        val result = outcome.toPaymentResult()

        assertEquals("", result.code)
        assertEquals("tok-1", result.storedMethod?.storedMethodId)
        assertEquals("", result.storedMethod?.resultText)
    }

    @Test
    fun `the response card is built from the fields the SDK exposes`() {
        // The SDK decodes the body and does not keep it, so this is a rendering of what came back. A field
        // the outcome carries and this omits is a field the card cannot show at all.
        val rendered = requireNotNull(capturedPaymentOutcome().result.apiResponse)

        assertEquals("A0000", rendered["code"]?.jsonPrimitive?.content)
        assertEquals("101-abc", rendered["paymentTransId"]?.jsonPrimitive?.content)
        assertEquals("gtw-9", rendered["gatewayTransId"]?.jsonPrimitive?.content)
        assertEquals("order-1", rendered["orderId"]?.jsonPrimitive?.content)
        assertEquals("1", rendered["transStatus"]?.jsonPrimitive?.content)
        assertEquals("1.10", rendered["totalAmount"]?.jsonPrimitive?.content)
        assertEquals("fiserv", rendered["connectorName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an absent field is left out of the response card rather than rendered as null`() {
        val rendered =
            requireNotNull(
                payment(totalAmount = null, netAmount = null, gatewayTransId = null).toPaymentResult().apiResponse,
            )

        assertFalse(rendered.containsKey("gatewayTransId"))
        assertFalse(rendered.containsKey("totalAmount"))
        assertTrue(rendered.containsKey("paymentTransId"))
    }

    @Test
    fun `a stored method's card names what was stored and never the token`() {
        val rendered = requireNotNull(storedMethodOutcome().result.apiResponse)

        assertEquals("true", rendered["isSuccess"]?.jsonPrimitive?.content)
        assertEquals("1", rendered["resultCode"]?.jsonPrimitive?.content)
        assertEquals("Approved", rendered["resultText"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a refusal keeps the reason and the detail apart`() {
        // `PaymentError.Payabli` stacks them into one message only when the detail adds something, which it
        // can only do if the two arrive separately.
        val error = refusedOutcome().error

        assertEquals(PaymentError.Payabli("Insufficient funds", "Try another card"), error)
        assertEquals("Insufficient funds\nTry another card", error.displayMessage)
    }

    @Test
    fun `every failure the SDK can raise arrives as a Payabli error, not as unexpected text`() {
        // The reason and the detail are `PayabliException`'s, so a transport failure and a decline both keep
        // the pair. Falling back to `Unexpected` would put a decline through the branch for "not from the SDK".
        val failures =
            listOf(
                PayInException.InvalidInput("paymentMethod.cardnumber", "The card number is not valid"),
                PayInException.Undecodable(IllegalStateException("unexpected token")),
                PayInException.Interrupted(),
                PayInException.AlreadySubmitting(),
            )

        failures.forEach { cause ->
            val error = PayInSubmissionState.Failed(cause).toPaymentError()
            assertTrue("$cause did not map to Payabli", error is PaymentError.Payabli)
            assertEquals(cause.reason, (error as PaymentError.Payabli).reason)
            assertTrue("$cause arrived with nothing to show", error.displayMessage.isNotBlank())
        }
    }

    @Test
    fun `no rendered outcome carries an idempotency key`() {
        // It is a credential for repeating a payment. The state holds one so a host can retry with it; the
        // screens show text and a card, and neither is a place for it.
        val error =
            PayInSubmissionState
                .Failed(PayInException.Interrupted(), retryKey = "key-1")
                .toPaymentError()

        assertFalse(error.displayMessage.contains("key-1"))
    }

    private fun payment(
        totalAmount: BigDecimal?,
        netAmount: BigDecimal?,
        gatewayTransId: String? = "gtw-9",
    ) = PayInSubmissionState.Succeeded.Payment(
        PayInResult(
            code = "A0000",
            reason = "Approved",
            explanation = null,
            action = null,
            transaction =
                PayInTransaction(
                    paymentTransId = "101-abc",
                    gatewayTransId = gatewayTransId,
                    orderId = "order-1",
                    method = "card",
                    transStatus = 1,
                    paypointId = 42,
                    totalAmount = totalAmount,
                    netAmount = netAmount,
                    connectorName = "fiserv",
                    customerId = 7,
                ),
        ),
    )

    // --- the calls that return rather than publishing ---

    /** An approval reached through a `Result`, which is how the two headless calls answer. */
    @Test
    fun `a returned approval carries the service's own words`() {
        val outcome =
            Result
                .success(
                    PayInResult(
                        code = "A0003",
                        reason = "Canceled",
                        explanation = "Transaction Canceled",
                        action = "No action required",
                        transaction = null,
                    ),
                ).toOutcome()

        val approved = outcome as PayInOutcome.Approved
        assertEquals("A0003", approved.result.code)
        assertEquals("Canceled", approved.result.reason)
        assertNull("a void names no transaction of its own", approved.result.transaction)
    }

    /**
     * All four fields the envelope carries, not just the code and the reason.
     *
     * The transaction screen shows explanation and action in their own rows, so dropping them here leaves two
     * rows reading as though the service sent nothing.
     */
    @Test
    fun `an approval keeps every word the service sent`() {
        val result =
            Result
                .success(
                    PayInResult(
                        code = "A0003",
                        reason = "Canceled",
                        explanation = "Transaction Canceled",
                        action = "No action required",
                        transaction = null,
                    ),
                ).toOutcome() as PayInOutcome.Approved

        assertEquals("A0003", result.result.code)
        assertEquals("Canceled", result.result.reason)
        assertEquals("Transaction Canceled", result.result.explanation)
        assertEquals("No action required", result.result.action)
    }

    /** A failure the SDK described, which is what a screen shows. */
    @Test
    fun `a returned failure reads as the platform failure it is`() {
        val refusal =
            PayInException.Refused(
                PayInFailure(
                    code = "E7002",
                    reason = "Invalid transaction status",
                    explanation = "The status of the transaction does not allow the action requested",
                    action = "r",
                    httpStatus = 400,
                ),
            )

        val refused = Result.failure<PayInResult>(refusal).toOutcome() as PayInOutcome.Refused

        val error = refused.error as PaymentError.Payabli
        assertEquals("Invalid transaction status", error.reason)
        // A returned failure carries no key: the caller's own is the only one, and it already holds it.
        assertFalse(refused.keepsItsIdempotencyKey)
    }

    /** Anything that is not a `PayabliException` cannot come from the SDK's own paths. */
    @Test
    fun `a failure from nowhere in the SDK reads as unexpected, without its message`() {
        val refused =
            Result.failure<PayInResult>(IllegalStateException("card 4111111111111111 exploded")).toOutcome()
                as PayInOutcome.Refused

        val error = refused.error as PaymentError.Unexpected
        assertEquals("java.lang.IllegalStateException", error.text)
        assertFalse(error.text.contains("4111111111111111"))
    }
}
