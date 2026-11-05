package com.payabli.example.app.demo.terminal

/** Which colour family a session state should read in. */
enum class ChipTone {
    Ready,
    Alert,
    Pending,
    Neutral,
}

/**
 * What the chip in the top app bar says and how it reads.
 *
 * A tone, so the mapping can be tested without a Compose runtime and the theme stays the only thing
 * that decides what "alert" looks like.
 */
data class SessionChipSpec(
    val label: String,
    val tone: ChipTone,
)

/**
 * Why the session stopped, for the step that offers to start it again. The session ends on its
 * own clock, so no action outcome describes it.
 *
 * @param state the session state the terminal reported.
 * @param reason the reason the SDK named for a failed session, or null when it named none.
 * @param deviceLine this app's line for the error that landed the ineligible reason: four error
 *   codes land it, and a line fixed to the reason cannot say which.
 * @return the line the step shows, or an empty string when the session has not stopped.
 */
fun sessionFailureReason(
    state: TerminalSessionState,
    reason: TerminalFailureReason? = null,
    deviceLine: String? = null,
): String =
    when (state) {
        // The ineligible landing is four error codes sharing one reason, and a line fixed to the
        // reason cannot say which, so the line chosen by the error's type is shown.
        TerminalSessionState.Error ->
            when (reason) {
                TerminalFailureReason.DeviceIneligible -> deviceLine ?: reason.message
                null -> "The session stopped."
                else -> reason.message
            }
        TerminalSessionState.SessionExpired -> "The session expired."
        else -> ""
    }

/**
 * The charge step's failure line: the ineligible landing outranks an earlier charge's outcome, because that
 * landing is the current failure. A charge that was or may have been taken keeps its warning above it.
 *
 * @param chargeFailure the outcome the last charge attempt left, or null when none did.
 * @param state the session state the terminal reported.
 * @param reason the reason the SDK named for a failed session, or null when it named none.
 * @param deviceLine this app's line for the error that landed the ineligible reason: four error
 *   codes land it, and a line fixed to the reason cannot say which.
 * @param chargeWarns [chargeFailure] says the card was or may have been charged.
 * @return the line the step shows, or an empty string when the session has not stopped.
 */
fun chargeStepFailure(
    chargeFailure: String?,
    state: TerminalSessionState,
    reason: TerminalFailureReason? = null,
    deviceLine: String? = null,
    chargeWarns: Boolean = false,
): String =
    when (reason) {
        TerminalFailureReason.DeviceIneligible -> {
            val device = deviceLine ?: reason.message
            if (chargeWarns && chargeFailure != null) "$chargeFailure\n$device" else device
        }
        else -> chargeFailure ?: sessionFailureReason(state, reason, deviceLine)
    }

fun chipSpecFor(state: TerminalSessionState): SessionChipSpec =
    when (state) {
        TerminalSessionState.Idle -> SessionChipSpec("Idle", ChipTone.Neutral)
        TerminalSessionState.AttestingDevice -> SessionChipSpec("Attesting", ChipTone.Pending)
        TerminalSessionState.FetchingConfig -> SessionChipSpec("Configuring", ChipTone.Pending)
        TerminalSessionState.InitializingReader -> SessionChipSpec("Starting reader", ChipTone.Pending)
        TerminalSessionState.Ready -> SessionChipSpec("Ready", ChipTone.Ready)
        TerminalSessionState.OpeningPayment -> SessionChipSpec("Opening payment", ChipTone.Pending)
        TerminalSessionState.WaitingForCard -> SessionChipSpec("Tap a card", ChipTone.Pending)
        TerminalSessionState.ClosingPayment -> SessionChipSpec("Closing payment", ChipTone.Pending)
        TerminalSessionState.SessionExpired -> SessionChipSpec("Expired", ChipTone.Alert)
        TerminalSessionState.Reinitializing -> SessionChipSpec("Restarting", ChipTone.Pending)
        // Pending: the device is fine and is waiting for someone to activate it. Nothing is broken.
        TerminalSessionState.PendingActivation -> SessionChipSpec("Needs activation", ChipTone.Pending)
        TerminalSessionState.Error -> SessionChipSpec("Error", ChipTone.Alert)
    }
