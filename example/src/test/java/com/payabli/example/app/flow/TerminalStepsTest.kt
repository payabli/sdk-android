package com.payabli.example.app.flow

import com.payabli.example.app.preflight.Readiness
import com.payabli.example.app.terminal.TerminalAction
import com.payabli.example.app.terminal.TerminalSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same rules as the card-not-present sequence, over every state the session can be in.
 *
 * This screen has the most states, and the only step that can be skipped.
 */
class TerminalStepsTest {
    /** Readiness, session, activation refused, charge failed, which action is in flight. */
    private data class Combination(
        val readiness: Readiness,
        val session: TerminalSessionState,
        val activationFailed: Boolean,
        val chargeFailed: Boolean,
        val working: TerminalAction?,
    )

    private val everyState: List<Combination> =
        Readiness.entries.flatMap { readiness ->
            TerminalSessionState.entries.flatMap { session ->
                listOf(true, false).flatMap { activationFailed ->
                    listOf(true, false).flatMap { chargeFailed ->
                        (TerminalAction.entries + null).map { working ->
                            Combination(readiness, session, activationFailed, chargeFailed, working)
                        }
                    }
                }
            }
        }

    private fun steps(c: Combination) =
        TerminalSteps.forCharging(c.readiness, c.session, c.activationFailed, c.chargeFailed, c.working)

    @Test
    fun `no two steps ask for something at once, in any session state`() {
        everyState.forEach { combination ->
            val sequence = steps(combination)
            assertTrue(
                "$combination produced ${sequence.map { it.status }}",
                sequence.isWellFormed(),
            )
        }
    }

    @Test
    fun `a failure is never reported by more than one step`() {
        everyState.forEach { combination ->
            val failed = steps(combination).count { it.status == StepStatus.Failed }
            assertTrue("$combination reported $failed failures", failed <= 1)
        }
    }

    @Test
    fun `the sequence always has four steps and every one says what it is`() {
        everyState.forEach { combination ->
            val sequence = steps(combination)
            assertEquals(4, sequence.size)
            sequence.forEach {
                assertTrue("a step has no title", it.title.isNotBlank())
                assertTrue("${it.title} says nothing about the SDK", it.detail.isNotBlank())
            }
        }
    }

    // --- the order ---

    @Test
    fun `a device that cannot run Tap to Pay stops the sequence at the first step`() {
        val sequence = TerminalSteps.forCharging(Readiness.NotAvailable, TerminalSessionState.Idle, false)
        assertEquals(StepStatus.Failed, sequence[0].status)
        assertTrue("the reason went without the checks", sequence[0].status.showsContent)
        assertTrue("a later step asked for something", sequence.drop(1).none { it.status.isActionable })
    }

    @Test
    fun `a ready device hands the sequence to turning the terminal on`() {
        val sequence = TerminalSteps.forCharging(Readiness.Ready, TerminalSessionState.Idle, false)
        assertEquals(StepStatus.Done, sequence[0].status)
        assertEquals(StepStatus.Current, sequence[1].status)
    }

    @Test
    fun `starting the terminal is the app waiting, not the reader`() {
        listOf(
            TerminalSessionState.AttestingDevice,
            TerminalSessionState.FetchingConfig,
            TerminalSessionState.InitializingReader,
            TerminalSessionState.Reinitializing,
        ).forEach { session ->
            val sequence = TerminalSteps.forCharging(Readiness.Ready, session, false)
            assertEquals(session.toString(), StepStatus.InProgress, sequence[1].status)
            assertTrue("$session asked for something while working", !sequence[1].status.isActionable)
        }
    }

    @Test
    fun `a session that stopped keeps the step that can start it again`() {
        listOf(TerminalSessionState.Error, TerminalSessionState.SessionExpired).forEach { session ->
            val sequence = TerminalSteps.forCharging(Readiness.Ready, session, false)
            assertEquals(session.toString(), StepStatus.Failed, sequence[1].status)
            assertTrue("$session hid its retry", sequence[1].status.showsContent)
        }
    }

    @Test
    fun `a device awaiting registration is asked for a code, and cannot charge yet`() {
        val sequence = TerminalSteps.forCharging(Readiness.Ready, TerminalSessionState.PendingActivation, false)
        assertEquals("turning it on got as far as it can", StepStatus.Done, sequence[1].status)
        assertEquals(StepStatus.Current, sequence[2].status)
        assertEquals(StepStatus.Blocked, sequence[3].status)
    }

    @Test
    fun `a refused activation keeps its own step and blocks the charge`() {
        val sequence = TerminalSteps.forCharging(Readiness.Ready, TerminalSessionState.PendingActivation, true)
        assertEquals(StepStatus.Failed, sequence[2].status)
        assertTrue("the code field went with the failure", sequence[2].status.showsContent)
        assertEquals(StepStatus.Blocked, sequence[3].status)
    }

    @Test
    fun `a terminal that never needed activation says so rather than pretending it is done`() {
        val sequence = TerminalSteps.forCharging(Readiness.Ready, TerminalSessionState.Ready, false)
        assertEquals(StepStatus.NotNeeded, sequence[2].status)
        assertEquals(StepStatus.Current, sequence[3].status)
    }

    @Test
    fun `an action in flight is the app working, not the reader`() {
        // The session reports Ready throughout a charge and PendingActivation throughout an
        // activation, so neither step can tell from it that it is running.
        val charging =
            TerminalSteps.forCharging(
                Readiness.Ready,
                TerminalSessionState.Ready,
                false,
                false,
                TerminalAction.Charge,
            )
        assertEquals(StepStatus.InProgress, charging[3].status)
        assertTrue("a running charge asked for something", !charging[3].status.isActionable)

        val activating =
            TerminalSteps.forCharging(
                Readiness.Ready,
                TerminalSessionState.PendingActivation,
                false,
                false,
                TerminalAction.Activate,
            )
        assertEquals(StepStatus.InProgress, activating[2].status)
    }

    @Test
    fun `a failed charge is reported by the step that took it`() {
        val sequence = TerminalSteps.forCharging(Readiness.Ready, TerminalSessionState.Ready, false, true, null)
        assertEquals(StepStatus.Failed, sequence[3].status)
        assertTrue("the retry went with the reason", sequence[3].status.showsContent)
    }

    @Test
    fun `a step that is working keeps what its controls are holding`() {
        // Hiding them disposes the composition, and the amount typed in goes with it.
        val charging =
            TerminalSteps.forCharging(
                Readiness.Ready,
                TerminalSessionState.Ready,
                false,
                false,
                TerminalAction.Charge,
            )
        assertTrue("the amount went off screen mid-charge", charging[3].status.showsContent)
    }

    @Test
    fun `every step a reader can act on shows its controls`() {
        everyState.forEach { combination ->
            steps(combination).filter { it.status.isActionable }.forEach { step ->
                assertTrue("${step.status} asks for something it does not show", step.status.showsContent)
            }
        }
    }

    @Test
    fun `starting the terminal does not read as a charge in flight`() {
        // The session reaches Ready before initialize() returns, so the charge step saw an action in
        // flight and a ready session and called itself working over somebody else's action.
        listOf(TerminalAction.Initialize, TerminalAction.Reinitialize).forEach { action ->
            val sequence =
                TerminalSteps.forCharging(Readiness.Ready, TerminalSessionState.Ready, false, false, action)
            assertEquals(action.toString(), StepStatus.Current, sequence[3].status)
        }
    }

    @Test
    fun `only the step an action belongs to reports it`() {
        everyState.filter { it.working != null }.forEach { combination ->
            val working = steps(combination).withIndex().filter { it.value.status == StepStatus.InProgress }
            working.forEach { (index, _) ->
                val expected =
                    when (index) {
                        2 -> TerminalAction.Activate
                        3 -> TerminalAction.Charge
                        // Step 2 reads the session, which reports its own progress.
                        else -> null
                    }
                if (expected != null) {
                    assertEquals("$combination", expected, combination.working)
                }
            }
        }
    }

    @Test
    fun `charging is only ever offered by a ready terminal`() {
        everyState.forEach { combination ->
            val charge = steps(combination)[3]
            if (charge.status.isActionable) {
                assertEquals(
                    "$combination offered a charge",
                    TerminalSessionState.Ready,
                    combination.session,
                )
            }
        }
    }
}
