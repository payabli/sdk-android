package com.payabli.example.app.demo.terminal

/**
 * A failed terminal action, with the line the screen shows kept apart from the exception. `message` is the
 * cause's own `message`, its classification, and `toString` names [code] when the failure has one.
 *
 * @property code the failure's catalog code, or null when it has none.
 * @property asksForSetup the operator's next step is setting the terminal up again, not another activation code.
 */
class TerminalFailure(
    val shown: String,
    cause: Throwable,
    val code: String? = null,
    val asksForSetup: Boolean = false,
) : Exception(cause.message, cause) {
    override fun toString(): String = code?.let { "TerminalFailure(code=$it)" } ?: "TerminalFailure(${cause?.message})"
}

/**
 * Whether the setup step asks to be run again once [action] has ended in [result].
 *
 * @param previous whether it asked before this action.
 * @param action the action that ended.
 * @param result how it ended.
 * @return true after an activation refused with [TerminalFailure.asksForSetup], false once a setup has finished, and
 *   [previous] after anything else.
 */
fun setupAskedAfter(
    previous: Boolean,
    action: TerminalAction,
    result: Result<*>,
): Boolean =
    when {
        action == TerminalAction.Activate -> (result.exceptionOrNull() as? TerminalFailure)?.asksForSetup == true
        action.setsUp -> false
        else -> previous
    }
