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
 * @param deviceMessage the thrown error's message when the ineligible reason landed: four error
 *   codes land it, and a line fixed to the reason cannot say which.
 * @return the line the step shows, or an empty string when the session has not stopped.
 */
fun sessionFailureReason(
    state: TerminalSessionState,
    reason: TerminalFailureReason? = null,
    deviceMessage: String? = null,
): String =
    when (state) {
        // The ineligible landing is four error codes sharing one reason, and a line fixed to the
        // reason cannot say which, so the thrown error's message is the line.
        TerminalSessionState.Error ->
            when (reason) {
                TerminalFailureReason.DeviceIneligible -> deviceMessage ?: reason.message
                null -> "The session stopped."
                else -> reason.message
            }
        TerminalSessionState.SessionExpired -> "The session expired."
        else -> ""
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
