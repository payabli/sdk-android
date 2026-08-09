package com.payabli.example.app.terminal

/** The four things the Tap to pay screen can ask the terminal to do. */
enum class TerminalAction(
    val label: String,
) {
    Initialize("Enable terminal"),
    Reinitialize("Re-initialize"),
    Charge("Charge"),
    Activate("Activate device"),
}

/**
 * The single line the screen shows after an action.
 *
 * A function over a [Result], so the four actions cannot drift into four different ways of reporting
 * the same thing, and so the wording is testable without a screen.
 */
object TerminalActionOutcome {
    fun success(
        action: TerminalAction,
        detail: String = "",
    ): String = if (detail.isEmpty()) "✓ ${action.label} succeeded" else "✓ ${action.label}: $detail"

    fun failure(
        action: TerminalAction,
        error: Throwable,
    ): String = "✗ ${action.label} failed: ${error.message ?: error.javaClass.simpleName}"

    /** The whole mapping in one place, so a caller never has to remember which half to call. */
    fun from(
        action: TerminalAction,
        result: Result<String>,
    ): String =
        result.fold(
            onSuccess = { success(action, it) },
            onFailure = { failure(action, it) },
        )
}
