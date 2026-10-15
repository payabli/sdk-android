package com.payabli.example.app.demo.simple

import com.payabli.example.app.demo.ui.customize.FormOperation
import com.payabli.example.app.demo.ui.customize.FormSettings
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.payment.PayInSubmissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the simple sample's next attempt is declared a retry.
 *
 * This screen sets `attempt` from what it holds, so the value here decides whether the next tap is the same
 * payment or another one. It keeps its own copy of the rule rather than reading the one in
 * `sdk/PayInOutcome.kt`, because the screen is meant to be read end to end without following a boundary.
 */
class SimpleCaptureKeyTest {
    @Test
    fun `an unresolved attempt makes the next one a retry`() {
        val dropped = failed(PayInException.Unsettled(network()))

        assertTrue(retryAfter(open = false, FormOperation.Capture, dropped))
    }

    @Test
    fun `a refused retry leaves the next one a retry`() {
        // The service has seen the payment and the outcome is still unknown, so a fresh payment is the one
        // thing the next tap must not be.
        val refused = failed(PayInException.Unsettled(refusedRetry()))

        assertTrue(retryAfter(open = true, FormOperation.Capture, refused))
    }

    @Test
    fun `an answered attempt ends the retry`() {
        // A decline settles the payment, so what the payer sends next is a different request.
        val declined = failed(SampleFailure(PayabliErrorType.PAYMENT_DECLINED, "Insufficient funds"))

        assertFalse(retryAfter(open = true, FormOperation.Capture, declined))
    }

    @Test
    fun `a store leaves the capture's open retry in place`() {
        val storeFailed = failed(SampleFailure(PayabliErrorType.PAYMENT_DECLINED, "Refused"))

        assertTrue(retryAfter(open = true, FormOperation.Tokenize, storeFailed))
        assertTrue(retryAfter(open = true, FormOperation.Tokenize, outcome = null))
    }

    @Test
    fun `a capture that succeeds ends the retry`() {
        assertFalse(retryAfter(open = true, FormOperation.Capture, outcome = null))
    }

    @Test
    fun `an authorization opens and ends a retry as a capture does`() {
        val dropped = failed(PayInException.Unsettled(network()))

        assertTrue(retryAfter(open = false, FormOperation.Authorize, dropped))
        assertFalse(retryAfter(open = true, FormOperation.Authorize, outcome = null))
    }

    private fun failed(cause: PayabliException) = PayInSubmissionState.Failed(cause)

    private fun network() = SampleFailure(PayabliErrorType.NETWORK_ERROR, "The request did not complete")

    private fun refusedRetry() = SampleFailure(PayabliErrorType.RETRY_REFUSED, "The service has seen this payment")

    @Test
    fun `the operation does not change while a submission is in flight`() {
        // The outcome is classified by the operation on screen when it arrives, so a switch mid-flight would
        // read a capture's outcome as a store's and drop the retry it needs.
        assertEquals(
            FormOperation.Capture,
            operationAfter(
                FormOperation.Capture,
                FormOperation.Tokenize,
                PayInSubmissionState.Submitting,
                heldFor = null,
            ),
        )
        assertEquals(
            FormOperation.Tokenize,
            operationAfter(FormOperation.Capture, FormOperation.Tokenize, PayInSubmissionState.Idle, heldFor = null),
        )
    }

    @Test
    fun `an open retry keeps the screen off every other operation that moves money`() {
        // The retry is of one payment. Declared by a different operation it would not reach that payment's
        // key, and the payment could then be charged again under a fresh one.
        val idle = PayInSubmissionState.Idle

        assertEquals(
            FormOperation.Capture,
            operationAfter(FormOperation.Capture, FormOperation.Authorize, idle, FormOperation.Capture),
        )
        assertEquals(
            FormOperation.Tokenize,
            operationAfter(FormOperation.Tokenize, FormOperation.Authorize, idle, FormOperation.Capture),
        )
        assertEquals(
            FormOperation.Tokenize,
            operationAfter(FormOperation.Capture, FormOperation.Tokenize, idle, FormOperation.Capture),
        )
        assertEquals(
            FormOperation.Capture,
            operationAfter(FormOperation.Tokenize, FormOperation.Capture, idle, FormOperation.Capture),
        )
        assertEquals(
            FormOperation.Authorize,
            operationAfter(FormOperation.Capture, FormOperation.Authorize, idle, heldFor = null),
        )
    }

    @Test
    fun `the operation an open retry belongs to stays offered with its setting off`() {
        // An authorization left a retry open, the screen moved to Tokenize, and Authorize was then turned off.
        val offered =
            offeredOperations(FormSettings(), current = FormOperation.Tokenize, heldFor = FormOperation.Authorize)

        assertEquals(listOf(FormOperation.Capture, FormOperation.Authorize, FormOperation.Tokenize), offered)
        assertEquals(
            listOf(FormOperation.Capture, FormOperation.Tokenize),
            offeredOperations(FormSettings(), current = FormOperation.Capture, heldFor = null),
        )
    }

    @Test
    fun `an open retry belongs to the operation that left it`() {
        assertEquals(FormOperation.Capture, retryOwnerAfter(null, FormOperation.Capture, retryOpen = true))
        assertEquals(
            FormOperation.Capture,
            retryOwnerAfter(FormOperation.Capture, FormOperation.Tokenize, retryOpen = true),
        )
        assertNull(retryOwnerAfter(FormOperation.Capture, FormOperation.Capture, retryOpen = false))
    }

    @Test
    fun `the amount is locked while a retry is open or a submission is in flight`() {
        // A retry is of one payment. Sending it with a different amount would retry that payment while the
        // screen shows another.
        assertFalse(amountEditable(PayInSubmissionState.Idle, retryOpen = true))
        assertFalse(amountEditable(PayInSubmissionState.Submitting, retryOpen = false))
        assertTrue(amountEditable(PayInSubmissionState.Idle, retryOpen = false))
    }

    @Test
    fun `a stored method is announced without naming the instrument`() {
        assertEquals("Payment method saved", outcomeMessage(FormOperation.Tokenize, succeeded = true))
        assertEquals("Payment approved", outcomeMessage(FormOperation.Capture, succeeded = true))
    }
}

private class SampleFailure(
    type: PayabliErrorType,
    reason: String,
) : PayabliException(type, reason)
