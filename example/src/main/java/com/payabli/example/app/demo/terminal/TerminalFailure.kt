package com.payabli.example.app.demo.terminal

/**
 * A failed terminal action, with the line the screen shows kept apart from the exception. `message` is the
 * cause's own `message`, its classification, and `toString` names [code] when the failure has one.
 *
 * @property code the failure's catalog code, or null when it has none.
 */
class TerminalFailure(
    val shown: String,
    cause: Throwable,
    val code: String? = null,
) : Exception(cause.message, cause) {
    override fun toString(): String = code?.let { "TerminalFailure(code=$it)" } ?: "TerminalFailure(${cause?.message})"
}
