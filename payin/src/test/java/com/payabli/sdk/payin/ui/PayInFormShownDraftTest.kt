package com.payabli.sdk.payin.ui

import com.payabli.sdk.core.model.PayabliErrorCategory
import com.payabli.sdk.payin.form.PayInFailureNotice
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormDraft
import com.payabli.sdk.payin.form.PayInFormMethod
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
    private val shown = holder.lease(PayInFormDraft())

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
        val other = holder.lease(second)

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
        shown.release()

        holder.lease(PayInFormDraft()).show(PayInFormDraft())

        assertTrue("the draft left behind stayed", first.typed.isEmpty())
    }

    @Test
    fun formsReturningInTheOtherOrderTakeTheSlotsTheyLeft() {
        val (first, second) = twoFormsThatLeft()

        holder.lease(second).show(second)
        holder.lease(first).show(first)

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
        assertEquals(TEST_NUMBER, second.typed[PayInField.CardNumber])
    }

    @Test
    fun aFormReturningWithAnotherFlowWhenTheSlotsCannotBeToldApartEmptiesEveryDraftLeft() {
        // The form returning cannot be matched to the slot it left, so no draft left behind is kept by guessing.
        val (first, second) = twoFormsThatLeft()

        holder.lease(PayInFormDraft()).show(PayInFormDraft())

        assertTrue("the second draft stayed", second.typed.isEmpty())
        assertTrue("the first draft stayed", first.typed.isEmpty())
    }

    @Test
    fun aDraftAFormOnScreenStillShowsIsNotEmptiedByAnAmbiguousReturn() {
        // Three forms at one position, two of them over the first flow. All leave, and one of those two returns.
        val first = filled()
        val second = filled()
        val left = listOf(first, second, first).map { draft -> holder.lease(draft).apply { show(draft) } }
        left.forEach { it.release() }
        holder.lease(first).show(first)

        holder.lease(PayInFormDraft()).show(PayInFormDraft())

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
        assertTrue("the second draft stayed", second.typed.isEmpty())
    }

    @Test
    fun aDraftAnotherFormWasHandedIsNotEmptiedWhenTheSlotThatDrewItIsReused() {
        // Three forms at one position leave; the second returns handed the first flow, then a new form arrives.
        val first = filled()
        val second = filled()
        val third = filled()
        listOf(first, second, third).map { draft -> holder.lease(draft).apply { show(draft) } }.forEach { it.release() }
        holder.lease(second).show(first)

        holder.lease(PayInFormDraft()).show(PayInFormDraft())

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
        assertTrue("the third draft stayed", third.typed.isEmpty())
    }

    @Test
    fun aFormHandedAnotherFlowLeavesTheDraftAnotherFormOnScreenStillShows() {
        val first = filled()
        val other = holder.lease(first)
        shown.show(first)
        other.show(first)

        other.show(PayInFormDraft())

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
    }

    @Test
    fun aReturnWhoseCompositionWasAbandonedEmptiesNothing() {
        val (first, second) = twoFormsThatLeft()
        holder.lease(PayInFormDraft()).release()

        holder.lease(first).show(first)

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
        assertEquals(TEST_NUMBER, second.typed[PayInField.CardNumber])
    }

    /** Two forms at one position, each showing its own filled draft, both gone from the composition. */
    private fun twoFormsThatLeft(): Pair<PayInFormDraft, PayInFormDraft> {
        val first = filled()
        val second = filled()
        shown.show(first)
        val other = holder.lease(second).apply { show(second) }
        shown.release()
        other.release()
        return first to second
    }

    private fun filled(): PayInFormDraft =
        PayInFormDraft().apply {
            seed(configuration, PayInFormMethod())
            enter(PayInField.CardNumber, TEST_NUMBER)
            rejectedFields = mapOf(PayInField.CardNumber to PayInFieldError.NotAccepted)
            failureNotice = PayInFailureNotice(PayabliErrorCategory.DECLINED, savesMethod = false)
        }

    private companion object {
        const val TEST_NUMBER = "4111111111111111"
    }
}
