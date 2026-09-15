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
     * A denial names the reader, not the step that met it, and every other failure names the step.
     *
     * The step list already draws this line: a refused reader leaves setup and activation done and fails the
     * payment step, because every call Payabli owns succeeded and the refusal is the card reader vendor's.
     * Wording this as "Set up the terminal failed" put a sentence on screen that the list beside it
     * contradicted, and the list is the one that is right.
     */
    fun failure(
        action: TerminalAction,
        error: Throwable,
        readerDenied: Boolean = false,
    ): String =
        if (readerDenied) {
            "✗ The card reader was refused: ${error.message ?: error.javaClass.simpleName}"
        } else {
            "✗ ${action.label} failed: ${error.message ?: error.javaClass.simpleName}"
        }

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
