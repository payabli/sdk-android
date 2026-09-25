package com.payabli.example.app.demo.simple

import com.payabli.example.app.demo.ui.customize.FormOperation
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.payment.PayInSubmissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which key the simple sample's next attempt carries.
 *
 * This screen sets `idempotencyKey` from what it holds, so the value here decides whether the next tap is
 * the same payment or another one. It keeps its own copy of the rule rather than reading the one in
 * `sdk/PayInOutcome.kt`, because the screen is meant to be read end to end without following a boundary.
 */
class SimpleCaptureKeyTest {
    @Test
    fun `an unresolved attempt hands its key to the next one`() {
        val dropped = failed(PayInException.Unsettled(network()), retryKey = "key-1")

        assertEquals("key-1", keyForNextAttempt(held = null, outcome = dropped))
    }

    @Test
    fun `a refused repeat keeps the key it was refused under`() {
        // The sequence that charges twice without this. A dropped request publishes key-1; the retry under
        // key-1 is answered 409, because the original had in fact succeeded; the SDK publishes no key,
        // since the service has just refused the only one there would be to send. Taking that null drops
        // key-1, and the next tap mints a fresh key and takes the payment a second time.
        val conflict = failed(PayInException.Unsettled(conflict()), retryKey = null)

        assertEquals("key-1", keyForNextAttempt(held = "key-1", outcome = conflict))
    }

    @Test
    fun `an answered attempt spends its key`() {
        // A decline settles the payment, so what the payer sends next is a different request and carrying
        // the old key would ask the service to replay a refusal.
        val declined = failed(SampleFailure(PayabliErrorCode.PAYMENT_DECLINED, "Insufficient funds"))

        assertNull(keyForNextAttempt(held = "key-1", outcome = declined))
    }

    @Test
    fun `a store leaves the capture's held key in place`() {
        val storeFailed = failed(SampleFailure(PayabliErrorCode.PAYMENT_DECLINED, "Refused"))

        assertEquals("key-1", keyAfter("key-1", FormOperation.Tokenize, storeFailed))
        assertEquals("key-1", keyAfter("key-1", FormOperation.Tokenize, outcome = null))
    }

    @Test
    fun `a capture that succeeds spends the held key`() {
        assertNull(keyAfter("key-1", FormOperation.Capture, outcome = null))
    }

    private fun failed(
        cause: PayabliException,
        retryKey: String? = null,
    ) = PayInSubmissionState.Failed(cause, retryKey = retryKey)

    private fun network() = SampleFailure(PayabliErrorCode.NETWORK_ERROR, "The request did not complete")

    private fun conflict() = SampleFailure(PayabliErrorCode.CONFLICT, "The service has seen this key")

    @Test
    fun `the operation does not change while a submission is in flight`() {
        // The outcome is classified by the operation on screen when it arrives, so a switch mid-flight would
        // read a capture's outcome as a store's and drop the key a retry needs.
        assertEquals(
            FormOperation.Capture,
            operationAfter(FormOperation.Capture, FormOperation.Tokenize, PayInSubmissionState.Submitting),
        )
        assertEquals(
            FormOperation.Tokenize,
            operationAfter(FormOperation.Capture, FormOperation.Tokenize, PayInSubmissionState.Idle),
        )
    }

    @Test
    fun `the amount is locked while a retry key is held or a submission is in flight`() {
        // A held key names one payment. Sending it with a different amount would retry that payment while the
        // screen shows another.
        assertFalse(amountEditable(PayInSubmissionState.Idle, retryKey = "key-1"))
        assertFalse(amountEditable(PayInSubmissionState.Submitting, retryKey = null))
        assertTrue(amountEditable(PayInSubmissionState.Idle, retryKey = null))
    }

    @Test
    fun `a stored method is announced without naming the instrument`() {
        assertEquals("Payment method saved", outcomeMessage(FormOperation.Tokenize, succeeded = true))
        assertEquals("Payment approved", outcomeMessage(FormOperation.Capture, succeeded = true))
    }
}

private class SampleFailure(
    code: PayabliErrorCode,
    reason: String,
) : PayabliException(code, reason)
