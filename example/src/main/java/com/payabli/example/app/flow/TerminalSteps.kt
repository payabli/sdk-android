package com.payabli.example.app.flow

import com.payabli.example.app.preflight.Readiness
import com.payabli.example.app.terminal.TerminalAction
import com.payabli.example.app.terminal.TerminalSessionState

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
                session == TerminalSessionState.Ready -> StepStatus.Done
                // Activation is a separate step, so reaching it means this one finished.
                session == TerminalSessionState.PendingActivation -> StepStatus.Done
                session in WORKING -> StepStatus.InProgress
                session in BROKEN -> StepStatus.Failed
                else -> StepStatus.Current
            }

        val activation =
            when {
                // Ordered. Reading the recorded outcome first lets a stale failure report itself
                // while the sequence is still on the step before.
                enable != StepStatus.Done -> StepStatus.Blocked
                // A terminal that reached Ready was activated already or never had to be, whatever
                // an earlier attempt did.
                session == TerminalSessionState.Ready -> StepStatus.NotNeeded
                working == TerminalAction.Activate &&
                    session == TerminalSessionState.PendingActivation -> StepStatus.InProgress
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
                title = "Turn on the terminal",
                detail = "The SDK attests the device, fetches its configuration and starts the reader.",
                status = enable,
            ),
            FlowStep(
                title = "Activate this device",
                detail = "A device the merchant has not registered needs an activation code once.",
                status = activation,
            ),
            FlowStep(
                title = "Take a payment",
                detail = "The reader waits for a card, and the SDK returns what it charged.",
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
