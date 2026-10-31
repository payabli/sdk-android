package com.payabli.example.app.demo.terminal

/** The four things the Tap to pay screen can ask the terminal to do. */
enum class TerminalAction(
    val label: String,
) {
    Initialize("Set up the terminal"),
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

    /**
     * The error's own words, with no step name: the step list beside the line shows which step failed.
     *
     * @param error the failure the action met, whose own words the line carries.
     * @return the line the screen shows for the action's outcome.
     */
    fun failure(error: Throwable): String = "✗ ${describe(error)}"

    private fun describe(error: Throwable): String =
        (error as? TerminalFailure)?.shown ?: error.message ?: error.javaClass.simpleName

    /** The whole mapping in one place, so a caller never has to remember which half to call. */
    fun from(
        action: TerminalAction,
        result: Result<String>,
    ): String =
        result.fold(
            onSuccess = { success(action, it) },
            onFailure = { failure(it) },
        )
}
