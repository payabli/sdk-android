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
import java.util.IdentityHashMap

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

    private val leases = IdentityHashMap<PayInFormDraft, PayInFormLease>()
    private val holder = PayInFormShownDraft()
    private val shown = holder.lease(PayInFormDraft())

    @Test
    fun theFirstDraftShownKeepsWhatWasTyped() {
        val first = filled()

        shown.draw(first)

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
    }

    @Test
    fun showingTheSameDraftAgainKeepsWhatWasTyped() {
        val first = filled()
        shown.draw(first)

        shown.draw(first)

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
        assertEquals(setOf(PayInField.CardNumber), first.rejectedFields.keys)
    }

    @Test
    fun showingAnotherDraftEmptiesTheOneShownBefore() {
        val first = filled()
        shown.draw(first)

        shown.draw(PayInFormDraft())

        assertTrue("typed values stayed: ${first.typed.keys.toList()}", first.typed.isEmpty())
        assertTrue("marks stayed: ${first.rejectedFields.keys}", first.rejectedFields.isEmpty())
        assertNull("the failure message stayed", first.failureNotice)
    }

    @Test
    fun aSubmissionInFlightStillDeliversAfterAnotherDraftIsShown() {
        val first = filled().apply { submissionPending = true }
        shown.draw(first)

        shown.draw(PayInFormDraft())

        assertTrue("the outcome in flight would never be delivered", first.submissionPending)
    }

    @Test
    fun theDraftShownNowIsLeftAlone() {
        shown.draw(filled())
        val second = filled()

        shown.draw(second)

        assertEquals(TEST_NUMBER, second.typed[PayInField.CardNumber])
    }

    @Test
    fun twoFormsHeldAtOnceDoNotEmptyEachOther() {
        val first = filled()
        val second = filled()
        val other = holder.lease(second)

        shown.draw(first)
        other.draw(second)
        shown.draw(first)

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
        assertEquals(TEST_NUMBER, second.typed[PayInField.CardNumber])
    }

    @Test
    fun aFormThatReturnsTakesTheSlotItLeft() {
        val first = filled()
        shown.draw(first)
        shown.leave()

        holder.lease(PayInFormDraft()).draw(PayInFormDraft())

        assertTrue("the draft left behind stayed", first.typed.isEmpty())
    }

    @Test
    fun formsReturningInTheOtherOrderTakeTheSlotsTheyLeft() {
        val (first, second) = twoFormsThatLeft()

        holder.lease(second).draw(second)
        holder.lease(first).draw(first)

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
        assertEquals(TEST_NUMBER, second.typed[PayInField.CardNumber])
    }

    @Test
    fun aFormReturningWithAnotherFlowWhenTheSlotsCannotBeToldApartEmptiesEveryDraftLeft() {
        // The form returning cannot be matched to the slot it left, so no draft left behind is kept by guessing.
        val (first, second) = twoFormsThatLeft()

        holder.lease(PayInFormDraft()).draw(PayInFormDraft())

        assertTrue("the second draft stayed", second.typed.isEmpty())
        assertTrue("the first draft stayed", first.typed.isEmpty())
    }

    @Test
    fun aDraftAFormOnScreenStillShowsIsNotEmptiedByAnAmbiguousReturn() {
        // Three forms at one position, two of them over the first flow. All leave, and one of those two returns.
        val first = filled()
        val second = filled()
        val left = listOf(first, second, first).map { draft -> holder.lease(draft).apply { draw(draft) } }
        left.forEach { it.leave() }
        holder.lease(first).draw(first)

        holder.lease(PayInFormDraft()).draw(PayInFormDraft())

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
        assertTrue("the second draft stayed", second.typed.isEmpty())
    }

    @Test
    fun aDraftAnotherFormWasHandedIsNotEmptiedWhenTheSlotThatDrewItIsReused() {
        // Three forms at one position leave; the second returns handed the first flow, then a new form arrives.
        val first = filled()
        val second = filled()
        val third = filled()
        listOf(first, second, third).map { draft -> holder.lease(draft).apply { draw(draft) } }.forEach { it.leave() }
        holder.lease(second).draw(first)

        holder.lease(PayInFormDraft()).draw(PayInFormDraft())

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
        assertTrue("the third draft stayed", third.typed.isEmpty())
    }

    @Test
    fun aFormHandedAnotherFlowLeavesTheDraftAnotherFormOnScreenStillShows() {
        val first = filled()
        val other = holder.lease(first)
        shown.draw(first)
        other.draw(first)

        other.draw(PayInFormDraft())

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
    }

    @Test
    fun aFormReturningWithAnotherFlowLeavesTheDraftAFormAtAnotherPositionNowShows() {
        val first = filled()
        shown.draw(first)
        shown.leave()
        PayInFormShownDraft().lease(first).draw(first)

        holder.lease(PayInFormDraft()).draw(PayInFormDraft())

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
    }

    @Test
    fun aReturnWhoseCompositionWasAbandonedEmptiesNothing() {
        val (first, second) = twoFormsThatLeft()
        holder.lease(PayInFormDraft()).release()

        holder.lease(first).draw(first)

        assertEquals(TEST_NUMBER, first.typed[PayInField.CardNumber])
        assertEquals(TEST_NUMBER, second.typed[PayInField.CardNumber])
    }

    /** Two forms at one position, each showing its own filled draft, both gone from the composition. */
    private fun twoFormsThatLeft(): Pair<PayInFormDraft, PayInFormDraft> {
        val first = filled()
        val second = filled()
        shown.draw(first)
        val other = holder.lease(second).apply { draw(second) }
        shown.leave()
        other.leave()
        return first to second
    }

    /** What a form drawing [draft] does: takes its flow's lease when free, gives back the one before, shows it. */
    private fun PayInFormShownDraft.Slot.draw(draft: PayInFormDraft) {
        drawn?.let { leaseOf(it).giveBack(this) }
        leaseOf(draft).apply { if (holder == null) holder = this@draw }
        show(draft, leaseOf(draft))
    }

    /** What a form leaving the composition does. */
    private fun PayInFormShownDraft.Slot.leave() {
        drawn?.let { leaseOf(it).giveBack(this) }
        release()
    }

    private fun PayInFormLease.giveBack(form: Any) {
        if (holder === form) holder = null
    }

    private fun leaseOf(draft: PayInFormDraft): PayInFormLease = leases.getOrPut(draft) { PayInFormLease() }

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
