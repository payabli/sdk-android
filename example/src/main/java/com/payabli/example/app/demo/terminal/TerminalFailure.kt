package com.payabli.example.app.demo.terminal

/**
 * A failed terminal action, with the line the screen shows kept apart from the exception. `message` and
 * `toString` read the cause's own `message`, which is its classification.
 */
class TerminalFailure(
    val shown: String,
    cause: Throwable,
) : Exception(cause.message, cause) {
    override fun toString(): String = "TerminalFailure(${cause?.message})"
}
