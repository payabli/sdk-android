package com.payabli.sdk.payin.ui

import com.payabli.sdk.core.model.PayabliErrorCategory
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.payin.R
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.model.PayInException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PayInFailureMessageTest {
    private fun failure(type: PayabliErrorType) = PayabliGenericException(type, "the service's own text")

    @Test
    fun `a failure picks the message by its own category`() {
        assertEquals(
            PayabliErrorCategory.DECLINED,
            PayInFailureMessage.categoryOf(failure(PayabliErrorType.PAYMENT_DECLINED)),
        )
        assertEquals(
            PayabliErrorCategory.RETRY_LATER,
            PayInFailureMessage.categoryOf(failure(PayabliErrorType.RATE_LIMITED)),
        )
        assertEquals(
            PayabliErrorCategory.CREDENTIAL,
            PayInFailureMessage.categoryOf(failure(PayabliErrorType.TOKEN_EXPIRED)),
        )
    }

    @Test
    fun `an unsettled payment reads as an unknown outcome whatever its cause's category`() {
        val unsettled = PayInException.Unsettled(failure(PayabliErrorType.RATE_LIMITED))

        assertEquals(PayabliErrorCategory.RETRY_LATER, unsettled.category)
        assertEquals(PayabliErrorCategory.OUTCOME_UNKNOWN, PayInFailureMessage.categoryOf(unsettled))
    }

    @Test
    fun `a failure the SDK did not classify reads as an unknown outcome`() {
        val converted = failure(PayabliErrorType.UNKNOWN)

        assertEquals(PayabliErrorCategory.OUTCOME_UNKNOWN, PayInFailureMessage.categoryOf(converted))
        assertEquals(
            R.string.payabli_payin_failure_payment_outcome_unknown,
            PayInFailureMessage.defaultText(PayInFailureMessage.categoryOf(converted), savesMethod = false),
        )
        assertEquals(
            R.string.payabli_payin_failure_save_other,
            PayInFailureMessage.defaultText(PayInFailureMessage.categoryOf(converted), savesMethod = true),
        )
    }

    @Test
    fun `a payment has one text per category, and every other category shares one`() {
        val expected =
            mapOf(
                PayabliErrorCategory.DECLINED to R.string.payabli_payin_failure_payment_declined,
                PayabliErrorCategory.RETRY_LATER to R.string.payabli_payin_failure_payment_retry_later,
                PayabliErrorCategory.OUTCOME_UNKNOWN to R.string.payabli_payin_failure_payment_outcome_unknown,
                PayabliErrorCategory.CREDENTIAL to R.string.payabli_payin_failure_payment_other,
                PayabliErrorCategory.CONFIGURATION to R.string.payabli_payin_failure_payment_other,
                PayabliErrorCategory.INVALID_REQUEST to R.string.payabli_payin_failure_payment_other,
                PayabliErrorCategory.DEVICE to R.string.payabli_payin_failure_payment_other,
                PayabliErrorCategory.INTERNAL to R.string.payabli_payin_failure_payment_other,
            )

        assertEquals(PayabliErrorCategory.entries.toSet(), expected.keys)
        expected.forEach { (category, text) ->
            assertEquals(category.name, text, PayInFailureMessage.defaultText(category, savesMethod = false))
        }
    }

    @Test
    fun `a save never says a payment was declined or may have gone through`() {
        val expected =
            mapOf(
                PayabliErrorCategory.DECLINED to R.string.payabli_payin_failure_save_declined,
                PayabliErrorCategory.RETRY_LATER to R.string.payabli_payin_failure_save_retry_later,
                PayabliErrorCategory.OUTCOME_UNKNOWN to R.string.payabli_payin_failure_save_other,
                PayabliErrorCategory.CREDENTIAL to R.string.payabli_payin_failure_save_other,
                PayabliErrorCategory.CONFIGURATION to R.string.payabli_payin_failure_save_other,
                PayabliErrorCategory.INVALID_REQUEST to R.string.payabli_payin_failure_save_other,
                PayabliErrorCategory.DEVICE to R.string.payabli_payin_failure_save_other,
                PayabliErrorCategory.INTERNAL to R.string.payabli_payin_failure_save_other,
            )

        assertEquals(PayabliErrorCategory.entries.toSet(), expected.keys)
        expected.forEach { (category, text) ->
            assertEquals(category.name, text, PayInFailureMessage.defaultText(category, savesMethod = true))
        }
    }

    @Test
    fun `the host's text replaces the category it is named for`() {
        val labels =
            PayInFormLabels(
                declinedMessage = "declined",
                retryLaterMessage = "later",
                outcomeUnknownMessage = "unknown",
                otherMessage = "other",
            )

        assertEquals("declined", PayInFailureMessage.hostText(PayabliErrorCategory.DECLINED, labels))
        assertEquals("later", PayInFailureMessage.hostText(PayabliErrorCategory.RETRY_LATER, labels))
        assertEquals("unknown", PayInFailureMessage.hostText(PayabliErrorCategory.OUTCOME_UNKNOWN, labels))
        assertEquals("other", PayInFailureMessage.hostText(PayabliErrorCategory.CREDENTIAL, labels))
        assertEquals("other", PayInFailureMessage.hostText(PayabliErrorCategory.INTERNAL, labels))
    }

    @Test
    fun `a blank or missing host text falls back to the SDK's own`() {
        val labels = PayInFormLabels(declinedMessage = "  ", retryLaterMessage = "")

        PayabliErrorCategory.entries.forEach { category ->
            assertNull(category.name, PayInFailureMessage.hostText(category, labels))
        }
    }
}
