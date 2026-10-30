package com.payabli.example.app

import com.payabli.example.app.demo.terminal.TerminalController
import com.payabli.example.app.sdk.TapToPayTerminal
import kotlinx.coroutines.MainScope
import java.util.WeakHashMap

/**
 * The card-present SDK, built once per container on first use.
 *
 * Built on first use for the reason [AppContainer.sessionSource] is: it captures [AppContainer.configuration],
 * and both writes that can change it land before any screen asks for a terminal.
 */
val AppContainer.terminal: TerminalController
    get() =
        synchronized(terminals) {
            terminals.getOrPut(this) {
                TapToPayTerminal(
                    appContext = appContext,
                    sessionSource = sessionSource,
                    entryPoint = configuration.entryPoint,
                    scope = MainScope(),
                    identity = sampleIdentity,
                )
            }
        }

private val terminals = WeakHashMap<AppContainer, TerminalController>()
