package com.payabli.example.app.demo.terminal

/**
 * A failed terminal action, with the line the screen shows kept apart from the exception.
 *
 * [shown] can carry text the service wrote, so it stays out of `message` and `toString`, which reach logs and
 * crash reports. Both read the cause's own `message`, which is its classification.
 */
class TerminalFailure(
    val shown: String,
    cause: Throwable,
) : Exception(cause.message, cause) {
    override fun toString(): String = "TerminalFailure(${cause?.message})"
}
