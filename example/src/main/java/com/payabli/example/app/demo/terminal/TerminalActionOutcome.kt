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
     * A denial carries the error's own words alone, and every other failure names the step.
     *
     * The step list leaves setup and activation done and fails the payment step, so a denial
     * naming the step contradicts the list beside it, and the list is the one that is right.
     *
     * @param action the action that failed, named in the line unless the reader was refused.
     * @param error the failure the action met, whose own words the line carries.
     * @param readerDenied true when the reader refused the device, so the line names no step.
     * @return the line the screen shows for the action's outcome.
     */
    fun failure(
        action: TerminalAction,
        error: Throwable,
        readerDenied: Boolean = false,
    ): String =
        if (readerDenied) {
            "✗ ${describe(error)}"
        } else {
            "✗ ${action.label} failed: ${describe(error)}"
        }

    private fun describe(error: Throwable): String =
        (error as? TerminalFailure)?.shown ?: error.message ?: error.javaClass.simpleName

    /** The whole mapping in one place, so a caller never has to remember which half to call. */
    fun from(
        action: TerminalAction,
        result: Result<String>,
        readerDenied: Boolean = false,
    ): String =
        result.fold(
            onSuccess = { success(action, it) },
            onFailure = { failure(action, it, readerDenied) },
        )
}
