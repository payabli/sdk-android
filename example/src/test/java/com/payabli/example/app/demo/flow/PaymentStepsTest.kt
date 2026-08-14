package com.payabli.example.app.demo.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a sequence follows, over every combination of state that can reach it.
 *
 * A sequence that offers two next things, or hides the reason a step failed, is wrong in a way no
 * rendering test would call out.
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
        // One way only. A working step shows its controls too, and is not asking for anything.
        everyCombination.forEach { flags ->
            steps(flags).filter { it.status.isActionable }.forEach { step ->
                assertTrue("${step.status} asks for something it does not show", step.status.showsContent)
            }
        }
    }

    @Test
    fun `the token check is never offered beside a form that can submit`() {
        // What keeps a recheck away from a payment in flight. A recheck builds a session and replaces the flow
        // the screen submits through, and a flow replaced while it holds an outcome strands it: the form is
        // gone, so nothing delivers it, and the view models refuse every later recheck while the flow is busy.
        //
        // The two are never on screen together, so a submission cannot be running when a recheck starts.
        // Rendering either one outside its step's status is what would open it.
        everyCombination.forEach { flags ->
            val sequence = steps(flags)
            assertFalse(
                "$flags offers a recheck beside a form that can submit",
                sequence[0].status.showsContent && sequence[1].status.showsContent,
            )
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
    fun `storing and capturing walk one sequence, named for what each is doing`() {
        // The statuses are the sequence and they are shared; the wording is per operation, because a payer
        // storing a method is not entering a payment and the result is not a transaction.
        val done = PaymentProgress(backendReachable = true, backendChecked = true, finished = true)
        val stored = PaymentSteps.forStoringMethod(done)
        val captured = PaymentSteps.forCapture(done)

        assertEquals(stored.map { it.status }, captured.map { it.status })
        assertEquals(stored.first().title, captured.first().title)
        assertTrue("the two forms read the same", stored[1].title != captured[1].title)
        assertTrue("the two results read the same", stored.last().title != captured.last().title)
    }

    @Test
    fun `a check in flight is the app working, not the reader`() {
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
