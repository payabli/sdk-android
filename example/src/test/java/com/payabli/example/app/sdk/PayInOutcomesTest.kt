package com.payabli.example.app.sdk

import com.payabli.example.app.demo.payment.FailureKind
import com.payabli.example.app.demo.payment.FailureText
import com.payabli.example.app.demo.payment.PaymentError
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.payin.PayInFormSuccess
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInFailure
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.model.PayInStoredMethod
import com.payabli.sdk.payin.model.PayInStoredMethodType
import com.payabli.sdk.payin.model.PayInTransaction
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
    fun `the fee is the one the service reported, and absent when it reported none`() {
        // Showing zero for an absent fee would claim the paypoint took none.
        val reported =
            payment(totalAmount = BigDecimal("1.10"), netAmount = BigDecimal("1.00"), feeAmount = BigDecimal("0.07"))
        val unreported = payment(totalAmount = BigDecimal("1.10"), netAmount = BigDecimal("1.00"))

        assertEquals("0.07", reported.toPaymentResult().transaction?.feeAmount)
        assertNull(unreported.toPaymentResult().transaction?.feeAmount)
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
            PayInFormSuccess
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
        assertEquals("a save's result code is the service's constant, so none crosses", "", result.code)
        assertNull("a stored method is not a transaction", result.transaction)
    }

    @Test
    fun `a stored method with no result code still describes what was stored`() {
        // The identifier is what the screen exists to show, and the service can approve without a code.
        val outcome =
            PayInFormSuccess.Method(
                PayInStoredMethod(
                    storedMethodId = "tok-1",
                    method = PayInStoredMethodType.Card,
                    methodReferenceId = null,
                    customerId = null,
                    resultCode = null,
                    resultText = null,
                ),
            )

        val result = outcome.toPaymentResult()

        assertEquals("", result.code)
        assertEquals("tok-1", result.storedMethod?.storedMethodId)
    }

    @Test
    fun `a refusal carries its category and its code, never its words`() {
        val error = refusedOutcome().error

        assertEquals(FailureKind.DECLINED, error.kind)
        assertFalse(error.code.isBlank())
        assertEquals("The payment was declined. Try another payment method.", FailureText.payment(error.kind))
    }

    @Test
    fun `an unknown outcome reads as one whatever its cause's category`() {
        // A declared retry refused before sending for a field is a validation error, and the payment it repeats may
        // still have gone through.
        val error =
            PayInException
                .Unsettled(PayInException.InvalidInput("paymentMethod.cardnumber", "The card number is not valid"))
                .toPaymentError()

        assertEquals(FailureKind.OUTCOME_UNKNOWN, error.kind)
    }

    @Test
    fun `a cancel reported as unsent says nothing was sent, and a later one still warns`() {
        val unsent = PayInException.Interrupted().toPaymentError()
        val afterSending = PayInException.Unsettled(PayInException.Interrupted()).toPaymentError()

        assertEquals(FailureKind.CANCELED_UNSENT, unsent.kind)
        assertEquals("The payment was canceled. Nothing was sent.", FailureText.payment(unsent.kind))
        assertEquals("The void was canceled. Nothing was sent.", FailureText.void(unsent.kind))
        assertEquals(
            "This payment may have gone through. Check before paying again.",
            FailureText.payment(afterSending.kind),
        )
    }

    @Test
    fun `a cancelled save may have stored the method, so it reads as unconfirmed`() {
        assertEquals(
            "We couldn't confirm the payment method was saved.",
            FailureText.save(PayInException.Interrupted().toPaymentError().kind),
        )
    }

    @Test
    fun `a save whose answer was lost says it is unconfirmed, and every other failure says it failed`() {
        assertEquals("We couldn't confirm the payment method was saved.", FailureText.save(FailureKind.OUTCOME_UNKNOWN))
        assertEquals("The payment method couldn't be saved.", FailureText.save(FailureKind.OTHER))
    }

    @Test
    fun `every failure the SDK can raise arrives with its code, and a screen has a text for it`() {
        val failures =
            listOf(
                PayInException.InvalidInput("paymentMethod.cardnumber", "The card number is not valid"),
                PayInException.Undecodable(IllegalStateException("unexpected token")),
                PayInException.Interrupted(),
                PayInException.AlreadySubmitting(),
            )

        failures.forEach { cause ->
            val error = cause.toPaymentError()
            assertEquals(cause.code.toString(), error.code)
            listOf(FailureText.payment(error.kind), FailureText.save(error.kind)).forEach { shown ->
                assertFalse("$cause showed its own text", shown.contains(cause.reason))
            }
        }
    }

    @Test
    fun `every failure records its catalog code, whether or not its own description names one`() {
        val invalid = PayInException.InvalidInput("paymentMethod.cardnumber", "The card number is not valid")

        val refused = Result.failure<PayInResult>(invalid).toOutcome() as PayInOutcome.Refused

        assertTrue(refused.diagnostic.endsWith("code=${invalid.code}"))
        assertFalse(refused.diagnostic.contains("The card number is not valid"))
    }

    private fun payment(
        totalAmount: BigDecimal?,
        netAmount: BigDecimal?,
        gatewayTransId: String? = "gtw-9",
        feeAmount: BigDecimal? = null,
    ) = PayInFormSuccess.Payment(
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
                    feeAmount = feeAmount,
                    surchargeFee = null,
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
        assertNull("a void names no transaction of its own", approved.result.transaction)
    }

    /** A failure the SDK described, whose code goes to the log. */
    @Test
    fun `a returned failure carries the code the SDK gave it`() {
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

        assertEquals(refusal.code.toString(), refused.error.code)
        // A returned failure carries no key: the caller's own is the only one, and it already holds it.
        assertFalse(refused.keepsItsPaymentReference)
    }

    @Test
    fun `an open outcome keeps the key, and so does one refused for a submission already in flight`() {
        val open =
            Result
                .failure<PayInResult>(
                    PayInException.Unsettled(
                        PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "the link dropped"),
                    ),
                ).toOutcome() as PayInOutcome.Refused
        val inFlight =
            Result.failure<PayInResult>(PayInException.AlreadySubmitting()).toOutcome() as PayInOutcome.Refused

        assertTrue(open.keepsItsPaymentReference)
        assertTrue(inFlight.keepsItsPaymentReference)
    }

    /** Anything that is not a `PayabliException` cannot come from the SDK's own paths. */
    @Test
    fun `a failure from nowhere in the SDK reads as unexpected, without its message`() {
        val refused =
            Result.failure<PayInResult>(IllegalStateException("card 4111111111111111 exploded")).toOutcome()
                as PayInOutcome.Refused

        assertEquals(PaymentError(FailureKind.OTHER, "java.lang.IllegalStateException"), refused.error)
    }
}
