package com.payabli.example.app.sdk

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliValidationException
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInFailure
import com.payabli.sdk.payin.payment.PayInSubmissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which failures leave the attempt's idempotency key worth sending again.
 *
 * The screens act on this and hold none of the SDK's failure types, so this is where the SDK's answer is
 * read. What a screen then does with it — mint a key, keep one — is the screen's rule and is tested there.
 */
class PayInOutcomeTest {
    @Test
    fun `an outcome that leaves the attempt unknown keeps its key`() {
        // The request may have reached the service and been taken in each of these: a read that timed out, a
        // 5xx, a 2xx that would not decode. Retried under a fresh key, all of them charge the payer twice.
        // Each carries the attempt's key, because that is what the SDK sets for exactly these.
        val unanswered =
            mapOf(
                "a read that timed out" to PayabliNetworkException(),
                "a service error" to PayInException.ServiceError(serviceFailure()),
                "a 2xx that would not decode" to PayInException.Undecodable(),
            )

        unanswered.forEach { (what, cause) ->
            val outcome = PayInSubmissionState.Failed(cause, retryKey = "the-attempt-key").toOutcome()

            assertTrue("$what said its key was spent", outcome.keepsItsIdempotencyKey)
        }
    }

    @Test
    fun `a submission refused while another is in flight keeps the key that one holds`() {
        // Nothing was sent, so no key is owed a retry, but the attempt still running is carrying this one.
        val outcome = PayInSubmissionState.Failed(PayInException.AlreadySubmitting()).toOutcome()

        assertTrue("the key the in-flight attempt holds was reported as spent", outcome.keepsItsIdempotencyKey)
    }

    @Test
    fun `an answered attempt has spent its key`() {
        // The service saw these and charged nothing. What the payer sends next is a different request, and
        // under the old key it asks the service to treat a changed body as a repeat.
        val answered =
            mapOf(
                "a decline" to PayInException.Refused(serviceFailure()),
                "a field the service rejected" to PayabliValidationException(httpStatus = 400),
                "a value refused before sending" to PayInException.InvalidInput(null, "Enter a card number"),
            )

        answered.forEach { (what, cause) ->
            val outcome = PayInSubmissionState.Failed(cause).toOutcome()

            assertFalse("$what kept a key the service has already answered", outcome.keepsItsIdempotencyKey)
        }
    }

    @Test
    fun `what a refusal records carries no server text`() {
        val outcome = PayInSubmissionState.Failed(PayInException.Refused(serviceFailure())).toOutcome()

        assertEquals("PayInException.Refused(code=D0001)", outcome.diagnostic)
    }
}

private class PayabliNetworkException :
    PayabliException(PayabliErrorCode.NETWORK_ERROR, "The request did not complete", "timeout")

private fun serviceFailure() =
    PayInFailure(code = "D0001", reason = "Insufficient funds", explanation = null, action = null, httpStatus = 200)
