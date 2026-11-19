package com.payabli.sdk.payin.ui

import com.payabli.sdk.core.model.PayabliErrorCategory
import com.payabli.sdk.payin.form.PayInFailureNotice
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormDraft
import com.payabli.sdk.payin.form.PayInMethodType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The form holds what the payer entered only for the flow it is showing. Handing it another flow empties the one
 * it showed before; showing the same flow again keeps it.
 */
class PayInFormShownDraftTest {
    private val configuration =
        PayInFormConfiguration(
            allowedMethods = listOf(PayInMethodType.Card),
            defaultMethod = PayInMethodType.Card,
        )

    private val holder = PayInFormShownDraft()
    private val shown = holder.lease()

    @Test
    fun theFirstDraftShownKeepsWhatWasTyped() {
        val first = filled()

        shown.show(first)

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
    }

    @Test
    fun showingTheSameDraftAgainKeepsWhatWasTyped() {
        val first = filled()
        shown.show(first)

        shown.show(first)

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
        assertEquals(setOf(PayInField.CardNumber), first.rejectedFields.keys)
    }

    @Test
    fun showingAnotherDraftEmptiesTheOneShownBefore() {
        val first = filled()
        shown.show(first)

        shown.show(PayInFormDraft())

        assertTrue("typed values stayed: ${first.typed.keys.toList()}", first.typed.isEmpty())
        assertTrue("marks stayed: ${first.rejectedFields.keys}", first.rejectedFields.isEmpty())
        assertNull("the failure message stayed", first.failureNotice)
    }

    @Test
    fun aSubmissionInFlightStillDeliversAfterAnotherDraftIsShown() {
        val first = filled().apply { submissionPending = true }
        shown.show(first)

        shown.show(PayInFormDraft())

        assertTrue("the outcome in flight would never be delivered", first.submissionPending)
    }

    @Test
    fun theDraftShownNowIsLeftAlone() {
        shown.show(filled())
        val second = filled()

        shown.show(second)

        assertEquals(TEST_NUMBER, second.typed[PayInField.CardNumber])
    }

    @Test
    fun twoFormsHeldAtOnceDoNotEmptyEachOther() {
        val first = filled()
        val second = filled()
        val other = holder.lease()

        shown.show(first)
        other.show(second)
        shown.show(first)

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
        assertEquals(TEST_NUMBER, second.typed[PayInField.CardNumber])
    }

    @Test
    fun aFormThatReturnsTakesTheSlotItLeft() {
        val first = filled()
        shown.show(first)
        shown.held = false

        holder.lease().show(PayInFormDraft())

        assertTrue("the draft left behind stayed", first.typed.isEmpty())
    }

    private fun filled(): PayInFormDraft =
        PayInFormDraft().apply {
            seed(configuration)
            enter(PayInField.CardNumber, TEST_NUMBER)
            rejectedFields = mapOf(PayInField.CardNumber to PayInFieldError.NotAccepted)
            failureNotice = PayInFailureNotice(PayabliErrorCategory.DECLINED, savesMethod = false)
        }

    private companion object {
        const val TEST_NUMBER = "4111111111111111"
    }
}
