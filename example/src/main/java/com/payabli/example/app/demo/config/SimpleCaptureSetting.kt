package com.payabli.example.app.demo.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the Simple Capture tab is shown.
 *
 * Off by default. The four capability tabs are what this app is for; the fifth is one screen showing the
 * fewest calls a capture takes, for a reader deciding what to copy.
 */
class SimpleCaptureSetting {
    private val _shown = MutableStateFlow(false)

    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    fun setShown(shown: Boolean) {
        _shown.value = shown
    }
}
