package com.payabli.example.app.demo.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.payabli.example.app.demo.ui.taptopay.TapToPayActions
import com.payabli.example.app.demo.ui.taptopay.TapToPayScreen
import com.payabli.example.app.demo.ui.taptopay.TapToPayViewModel

/** Whether the bar draws the Tap to Pay tab. This variant links the card reader, so it does. */
internal const val CARD_PRESENT_IN_BUILD = true

/** The Tap to Pay screen's graph. */
internal fun NavGraphBuilder.cardPresentGraph() {
    navigation<TapToPayGraph>(startDestination = TapToPayHome) {
        composable<TapToPayHome> {
            val model = demoViewModel { TapToPayViewModel.from(it) }
            val state by model.uiState.collectAsStateWithLifecycle()
            TapToPayScreen(
                state = state,
                actions = remember(model) { TapToPayActions.from(model) },
            )
        }
    }
}
