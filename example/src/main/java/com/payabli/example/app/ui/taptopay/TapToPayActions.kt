package com.payabli.example.app.ui.taptopay

import androidx.compose.runtime.Immutable

/**
 * Everything the tap-to-pay screen can ask for, in one value.
 *
 * The screen drives a state machine, so it has a lot of actions, and passing each as its own
 * parameter left a signature no reader takes in at once and a preview that had to be edited for
 * every one added. Grouped, the screen takes a state and the things that change it.
 *
 * No defaults. An action left unwired would otherwise compile into a button that does nothing.
 *
 * [Immutable] so the screen stays skippable: the compiler cannot tell that a holder of lambdas never
 * changes, and without it every recomposition of the graph would redraw the whole screen. Build it
 * once per view model and hold it.
 */
@Immutable
data class TapToPayActions(
    val onAmountChange: (String) -> Unit,
    val onActivationCodeChange: (String) -> Unit,
    val onEnable: () -> Unit,
    val onReinitialize: () -> Unit,
    val onCharge: () -> Unit,
    val onOpenActivation: () -> Unit,
    val onDismissActivation: () -> Unit,
    val onActivate: () -> Unit,
    val onClearEvents: () -> Unit,
    val onRecheck: () -> Unit,
    val onProbeToken: () -> Unit,
) {
    companion object {
        fun from(model: TapToPayViewModel): TapToPayActions =
            TapToPayActions(
                onAmountChange = model::setAmount,
                onActivationCodeChange = model::setActivationCode,
                onEnable = model::enableTerminal,
                onReinitialize = model::reinitialize,
                onCharge = model::charge,
                onOpenActivation = model::openActivation,
                onDismissActivation = model::dismissActivation,
                onActivate = model::activate,
                onClearEvents = model::clearEvents,
                onRecheck = model::recheck,
                onProbeToken = model::probeToken,
            )

        /** For previews, where nothing is wired and nothing should happen. */
        fun none(): TapToPayActions =
            TapToPayActions(
                onAmountChange = {},
                onActivationCodeChange = {},
                onEnable = {},
                onReinitialize = {},
                onCharge = {},
                onOpenActivation = {},
                onDismissActivation = {},
                onActivate = {},
                onClearEvents = {},
                onRecheck = {},
                onProbeToken = {},
            )
    }
}
