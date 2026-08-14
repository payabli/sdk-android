package com.payabli.example.app.demo.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A bounded, oldest-first log of redacted request and response lines.
 *
 * Bounded because this is on screen: an unbounded list would turn a long session into an unscrollable
 * wall and hold every line in memory for the life of the process. [LIMIT] is the number of entries a
 * reader can plausibly scan.
 *
 * Nothing sensitive reaches this. Callers pass lines that are already redacted; a store cannot make a
 * value safe after the fact, so it does not try, and no card number, token, expiry or account number
 * is ever handed to it.
 */
class DiagnosticsStore(
    private val limit: Int = LIMIT,
) {
    private val _messages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = _messages.asStateFlow()

    fun record(message: String) {
        _messages.update { current ->
            // Oldest-first, so a reader follows a request from the top down; the drop is from the
            // front for the same reason.
            val appended = current + message
            if (appended.size > limit) appended.takeLast(limit) else appended
        }
    }

    fun clear() {
        _messages.value = emptyList()
    }

    companion object {
        const val LIMIT: Int = 20
    }
}
