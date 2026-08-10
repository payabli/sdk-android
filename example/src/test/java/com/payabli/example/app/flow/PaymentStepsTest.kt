package com.payabli.example.app.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a sequence follows, over every combination of state that can reach it.
 *
 * These are the reason the derivation is a pure function rather than a handful of conditions inside
 * a composable: a sequence that offers two next things, or hides the reason a step failed, is wrong
 * in a way no rendering test would call out.
 */
class PaymentStepsTest {
    private val everyCombination: List<List<Boolean>> =
        (0 until 64).map { bits -> (0 until 6).map { bit -> bits shr bit and 1 == 1 } }

    private fun steps(flags: List<Boolean>) =
        PaymentSteps.forStoringMethod(
            PaymentProgress(
                backendReachable = flags[0],
                backendChecked = flags[1],
                isCheckingBackend = flags[5],
                isSubmitting = flags[2],
                submitFailed = flags[3],
                finished = flags[4],
            ),
        )

    @Test
    fun `no two steps ask for something at once`() {
        everyCombination.forEach { flags ->
            val sequence = steps(flags)
            assertTrue(
                "$flags produced ${sequence.map { it.status }}",
                sequence.isWellFormed(),
            )
        }
    }

    @Test
    fun `a failure is never reported by more than one step`() {
        everyCombination.forEach { flags ->
            val failed = steps(flags).count { it.status == StepStatus.Failed }
            assertTrue("$flags reported $failed failures", failed <= 1)
        }
    }

    @Test
    fun `every step a reader can act on shows its controls`() {
        // The property the whole layout rests on. The converse does not hold: a step that is working
        // keeps its controls on screen, disabled, so the payment form does not lose what was typed
        // into it the moment a submission starts.
        everyCombination.forEach { flags ->
            steps(flags).filter { it.status.isActionable }.forEach { step ->
                assertTrue("${step.status} asks for something it does not show", step.status.showsContent)
            }
        }
    }

    // --- the order itself ---

    @Test
    fun `an unchecked backend is the first thing asked for`() {
        val sequence = steps(listOf(false, false, false, false, false, false))
        assertEquals(StepStatus.Current, sequence[0].status)
        assertEquals(StepStatus.Blocked, sequence[1].status)
        assertEquals(StepStatus.Blocked, sequence[2].status)
    }

    @Test
    fun `a backend that answered hands the sequence to the form`() {
        val sequence = steps(listOf(true, true, false, false, false, false))
        assertEquals(StepStatus.Done, sequence[0].status)
        assertEquals(StepStatus.Current, sequence[1].status)
    }

    @Test
    fun `a backend that did not answer keeps the sequence and its retry`() {
        val sequence = steps(listOf(false, true, false, false, false, false))
        assertEquals(StepStatus.Failed, sequence[0].status)
        assertTrue("the retry went with the reason", sequence[0].status.showsContent)
        assertEquals(StepStatus.Blocked, sequence[1].status)
    }

    @Test
    fun `submitting is the app waiting, not the reader`() {
        val sequence = steps(listOf(true, true, true, false, false, false))
        assertEquals(StepStatus.InProgress, sequence[1].status)
        assertTrue("a submission in flight asked for something", !sequence[1].status.isActionable)
        assertTrue("the form went off screen mid-submission", sequence[1].status.showsContent)
    }

    @Test
    fun `a failed submission keeps its controls and leaves the result waiting`() {
        val sequence = steps(listOf(true, true, false, true, false, false))
        assertEquals(StepStatus.Failed, sequence[1].status)
        assertTrue(sequence[1].status.showsContent)
        assertEquals("the result must not read as failed too", StepStatus.Blocked, sequence[2].status)
    }

    @Test
    fun `a finished submission moves the sequence to the result`() {
        val sequence = steps(listOf(true, true, false, false, true, false))
        assertEquals(StepStatus.Done, sequence[1].status)
        assertEquals(StepStatus.Current, sequence[2].status)
    }

    @Test
    fun `storing and capturing differ only in what the last step is called`() {
        val done = PaymentProgress(backendReachable = true, backendChecked = true, finished = true)
        val stored = PaymentSteps.forStoringMethod(done)
        val captured = PaymentSteps.forCapture(done)

        assertEquals(stored.map { it.status }, captured.map { it.status })
        assertEquals(stored.take(2).map { it.title }, captured.take(2).map { it.title })
        assertTrue("the two results read the same", stored.last().title != captured.last().title)
    }

    @Test
    fun `a check in flight is the app working, not the reader`() {
        // Without this the step kept saying "do this next" and kept its button while the request it
        // started was still running.
        val sequence = steps(listOf(false, false, false, false, false, true))
        assertEquals(StepStatus.InProgress, sequence[0].status)
        assertTrue("a running check asked for something", !sequence[0].status.isActionable)
        assertEquals(StepStatus.Blocked, sequence[1].status)
    }

    @Test
    fun `every step says what it is and what the SDK does there`() {
        everyCombination.forEach { flags ->
            steps(flags).forEach { step ->
                assertTrue("a step has no title", step.title.isNotBlank())
                assertTrue("${step.title} says nothing about the SDK", step.detail.isNotBlank())
            }
        }
    }
}
