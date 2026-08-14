package com.payabli.example.app.demo.terminal
import com.payabli.example.app.demo.diagnostics.DiagnosticsStore

/**
 * The visible tail of the event stream.
 *
 * Newest first: the interesting event during a payment is the one that just happened, and a reader
 * should not have to scroll to reach it. Bounded for the same reason
 * [DiagnosticsStore] is: this is on screen, and a long session
 * would otherwise grow without limit.
 *
 * A value type, so it can live inside an immutable UI state.
 */
data class EventBuffer(
    val entries: List<TerminalEvent> = emptyList(),
    val limit: Int = LIMIT,
) {
    fun add(event: TerminalEvent): EventBuffer {
        val prepended = listOf(event) + entries
        return copy(entries = if (prepended.size > limit) prepended.take(limit) else prepended)
    }

    fun cleared(): EventBuffer = copy(entries = emptyList())

    val isEmpty: Boolean get() = entries.isEmpty()

    companion object {
        const val LIMIT: Int = 100
    }
}
