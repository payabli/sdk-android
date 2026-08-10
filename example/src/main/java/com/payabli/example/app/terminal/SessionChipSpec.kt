package com.payabli.example.app.terminal

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
 * Why the session stopped, for the step that offers to start it again. Empty when it has not.
 *
 * The session ends on its own clock, so no action outcome describes it.
 */
fun sessionFailureReason(state: TerminalSessionState): String =
    when (state) {
        TerminalSessionState.Error -> "The session stopped."
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
        TerminalSessionState.SessionExpired -> SessionChipSpec("Expired", ChipTone.Alert)
        TerminalSessionState.Reinitializing -> SessionChipSpec("Restarting", ChipTone.Pending)
        // Pending: the device is fine and is waiting for someone to activate it. Nothing is broken.
        TerminalSessionState.PendingActivation -> SessionChipSpec("Needs activation", ChipTone.Pending)
        TerminalSessionState.Error -> SessionChipSpec("Error", ChipTone.Alert)
    }
