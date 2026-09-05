package com.payabli.example.app.demo.flow

import com.payabli.example.app.demo.preflight.Readiness
import com.payabli.example.app.demo.terminal.TerminalAction
import com.payabli.example.app.demo.terminal.TerminalSessionState

/**
 * What taking a contactless payment asks for, in the order the SDK needs it.
 *
 * Pure, and outside `ui`, so the sequence can be checked against every state the session can be in.
 */
object TerminalSteps {
    /**
     * @param readiness what the device checks concluded.
     * @param session where the terminal session has got to.
     * @param activationFailed the last activation attempt was refused.
     * @param activated an activation succeeded. The session cannot say so: it reports
     *   [TerminalSessionState.Ready] both for a device that was activated and for one that never
     *   had to be, and those are a finished step and a skipped one.
     * @param chargeFailed the last charge attempt failed.
     * @param working which action is in flight, or null. The session reports
     *   [TerminalSessionState.Ready] throughout a charge and [TerminalSessionState.PendingActivation]
     *   throughout an activation, so it cannot say on its own that either is running. Which one
     *   matters as much as whether: the session reaches Ready before the call that took it there
     *   returns, so a bare flag marks the charge step in progress while the terminal is still
     *   starting.
     */
    fun forCharging(
        readiness: Readiness,
        session: TerminalSessionState,
        activationFailed: Boolean,
        chargeFailed: Boolean = false,
        working: TerminalAction? = null,
        activated: Boolean = false,
        readerDenied: Boolean = false,
    ): List<FlowStep> {
        val device =
            when (readiness) {
                Readiness.Ready -> StepStatus.Done
                Readiness.ActionNeeded -> StepStatus.Current
                Readiness.NotAvailable -> StepStatus.Failed
            }

        val enable =
            when {
                device != StepStatus.Done -> StepStatus.Blocked
                // Before the session is read. The call that starts the terminal publishes its final
                // state and stays suspended after it, so reading the session alone marked this step
                // done and handed the sequence on while the work was still running.
                working == TerminalAction.Initialize ||
                    working == TerminalAction.Reinitialize -> StepStatus.InProgress
                session == TerminalSessionState.Ready -> StepStatus.Done
                // Activation is a separate step, so reaching it means this one finished.
                session == TerminalSessionState.PendingActivation -> StepStatus.Done
                // Activating chains an initialize, which walks back through this step's own states.
                // Without this the spinner jumps back here while step 3 is the one running.
                working == TerminalAction.Activate -> StepStatus.Done
                session in WORKING -> StepStatus.InProgress
                // Before BROKEN, which this session is: every call this step makes succeeded, and the
                // refusal belongs to the step that cannot happen.
                readerDenied -> StepStatus.Done
                session in BROKEN -> StepStatus.Failed
                else -> StepStatus.Current
            }

        val activation =
            when {
                // Ordered. Reading the recorded outcome first lets a stale failure report itself
                // while the sequence is still on the step before.
                enable != StepStatus.Done -> StepStatus.Blocked
                // Before the session, for the same reason step 2 is: activating publishes Ready and
                // stays suspended after it, so reading the session first finished this step and
                // handed on the one after it while the activation was still running.
                working == TerminalAction.Activate -> StepStatus.InProgress
                // Done and NotNeeded both let the next step run. They say different things to a
                // reader, and only the caller knows which happened.
                activated -> StepStatus.Done
                session == TerminalSessionState.Ready -> StepStatus.NotNeeded
                // Before the recorded failure: activating chains an initialize, so a denied reader
                // surfaces as this call throwing after the code was accepted.
                readerDenied -> StepStatus.Done
                // The session cannot tell a refused activation from one that was never needed, so
                // the outcome is recorded and read here.
                activationFailed -> StepStatus.Failed
                session == TerminalSessionState.PendingActivation -> StepStatus.Current
                else -> StepStatus.NotNeeded
            }

        val charge =
            when {
                // From the step before. Checking only for a ready session let a device whose checks
                // had not passed offer a charge alongside the check it was still asking for.
                !activation.isFinished -> StepStatus.Blocked
                // The session never reached Ready, so nothing below would report this at all.
                readerDenied -> StepStatus.Failed
                working == TerminalAction.Charge && session == TerminalSessionState.Ready -> StepStatus.InProgress
                // The session stays Ready through a failed charge, so the outcome is recorded and
                // read here or step 4 never reports one.
                chargeFailed && session == TerminalSessionState.Ready -> StepStatus.Failed
                session == TerminalSessionState.Ready -> StepStatus.Current
                else -> StepStatus.Blocked
            }

        return listOf(
            FlowStep(
                title = "Check the device",
                detail = "Tap to Pay needs particular hardware, an OS floor and a signed build.",
                status = device,
            ),
            FlowStep(
                title = "Set up the terminal",
                detail = "The SDK attests the device and fetches its configuration.",
                status = enable,
            ),
            FlowStep(
                title = "Activate this device",
                detail = "A device the merchant has not registered needs an activation code once.",
                status = activation,
            ),
            FlowStep(
                title = "Take a payment",
                detail = "The reader is already up, so this waits for a card and returns what it charged.",
                status = charge,
            ),
        )
    }

    /** The session is doing something and the app is waiting on it. */
    private val WORKING =
        setOf(
            TerminalSessionState.AttestingDevice,
            TerminalSessionState.FetchingConfig,
            TerminalSessionState.InitializingReader,
            TerminalSessionState.Reinitializing,
        )

    /** The session stopped, and starting it again is the way forward. */
    private val BROKEN =
        setOf(
            TerminalSessionState.Error,
            TerminalSessionState.SessionExpired,
        )
}
