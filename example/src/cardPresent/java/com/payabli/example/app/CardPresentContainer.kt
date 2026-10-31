package com.payabli.example.app

import com.payabli.example.app.demo.terminal.TerminalController
import com.payabli.example.app.sdk.TapToPayTerminal
import kotlinx.coroutines.MainScope

/**
 * The card-present SDK for one [AppContainer].
 *
 * @param container The container whose session and configuration the terminal uses.
 */
class CardPresentContainer(
    private val container: AppContainer,
) {
    /**
     * Built on first use for the reason [AppContainer.sessionSource] is: it captures
     * [AppContainer.configuration], and both writes that can change it land before any screen asks for a
     * terminal.
     */
    val terminal: TerminalController by lazy {
        TapToPayTerminal(
            appContext = container.appContext,
            sessionSource = container.sessionSource,
            entryPoint = container.configuration.entryPoint,
            scope = MainScope(),
            identity = container.sampleIdentity,
        )
    }
}
