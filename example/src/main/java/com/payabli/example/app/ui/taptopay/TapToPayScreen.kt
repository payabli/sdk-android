package com.payabli.example.app.ui.taptopay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.config.DemoEnvironment
import com.payabli.example.app.config.TokenHostDefaults
import com.payabli.example.app.config.TokenHostResolver
import com.payabli.example.app.terminal.DemoTerminalController
import com.payabli.example.app.terminal.EventBuffer
import com.payabli.example.app.terminal.TerminalEvent
import com.payabli.example.app.terminal.TerminalEventCode
import com.payabli.example.app.terminal.TerminalSessionState
import com.payabli.example.app.terminal.chipSpecFor
import com.payabli.example.app.ui.components.BorderedButton
import com.payabli.example.app.ui.components.DemoIcons
import com.payabli.example.app.ui.components.DemoScreen
import com.payabli.example.app.ui.components.DetailRow
import com.payabli.example.app.ui.components.EventRow
import com.payabli.example.app.ui.components.PreviewSurface
import com.payabli.example.app.ui.components.ProminentButton
import com.payabli.example.app.ui.components.ReadinessCard
import com.payabli.example.app.ui.components.ResultCard
import com.payabli.example.app.ui.components.SectionHeader
import com.payabli.example.app.ui.components.StateChip
import com.payabli.example.app.ui.theme.Dimens

/**
 * Take a contactless payment on this device.
 *
 * The order is the order the work happens in: what is configured, whether the device can do this at
 * all, turning the terminal on, taking a payment, activating, and finally what happened.
 */
@Composable
fun TapToPayScreen(
    state: TapToPayUiState,
    actions: TapToPayActions,
    modifier: Modifier = Modifier,
) {
    DemoScreen(
        title = "Tap to pay",
        modifier = modifier,
        actions = {
            StateChip(
                spec = chipSpecFor(state.session),
                modifier = Modifier.padding(end = Dimens.CardPadding),
            )
        },
    ) {
        TerminalBlock(state, actions.onProbeToken)

        Block(title = "This device") {
            ReadinessCard(
                readiness = state.readiness,
                problems = state.problems,
                onRecheck = actions.onRecheck,
            )
        }

        ControlBlock(state, actions.onEnable, actions.onReinitialize)

        PaymentBlock(state, actions.onAmountChange, actions.onCharge)

        Block(title = "Activation") {
            BorderedButton(
                text = "Activate this device",
                icon = DemoIcons.Activate,
                onClick = actions.onOpenActivation,
                enabled = !state.isWorking,
            )
            Caption(
                "Needed when the session reports that the device is not activated. " +
                    "Payabli issues the code.",
            )
        }

        Block(title = "Last result") {
            ResultCard(text = state.resultText, emptyText = "Nothing done yet")
        }

        ActivityBlock(state, actions.onClearEvents)
    }

    if (state.isActivationOpen) {
        ActivationSheet(state, actions.onActivationCodeChange, actions.onActivate, actions.onDismissActivation)
    }
}

@Composable
private fun TerminalBlock(
    state: TapToPayUiState,
    onProbeToken: () -> Unit,
) {
    Block(title = "Terminal", note = "What the terminal needs before it can start.") {
        DetailRow(
            label = "Entry point",
            value = state.configuration.entryPoint,
            problem = state.configuration.entryPointProblem,
        )
        DetailRow(label = "App ID", value = state.configuration.appId)
        // Also on Setup. The terminal initialises against this value, so it is checkable without
        // leaving the screen that uses it.
        DetailRow(
            label = "Environment",
            value = "${state.configuration.environment.label} · ${state.configuration.environment.host}",
        )
        // The route the button calls, not the host it lives on.
        DetailRow(label = "Token endpoint", value = state.tokenServer.accessTokenUrl)
        DetailRow(label = "Chosen because", value = state.tokenServer.explanation)
        BorderedButton(
            text = "Check token",
            icon = DemoIcons.CheckToken,
            onClick = onProbeToken,
            enabled = !state.isWorking,
        )
        if (state.tokenProbeText.isNotEmpty()) {
            Caption(state.tokenProbeText)
        }
    }
}

@Composable
private fun ControlBlock(
    state: TapToPayUiState,
    onEnable: () -> Unit,
    onReinitialize: () -> Unit,
) {
    Block(title = "Terminal control") {
        ProminentButton(
            text = if (state.isReady) "Terminal is on" else "Turn on the terminal",
            icon = if (state.isReady) DemoIcons.Pass else DemoIcons.TapToPay,
            onClick = onEnable,
            enabled = !state.isWorking && !state.isReady,
        )
        BorderedButton(
            text = "Restart the session",
            icon = DemoIcons.Reinitialize,
            onClick = onReinitialize,
            enabled = !state.isWorking,
        )
    }
}

@Composable
private fun PaymentBlock(
    state: TapToPayUiState,
    onAmountChange: (String) -> Unit,
    onCharge: () -> Unit,
) {
    Block(title = "Take a payment") {
        OutlinedTextField(
            value = state.amountText,
            onValueChange = onAmountChange,
            label = { Text("Amount") },
            prefix = { Text("$") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        ProminentButton(
            text = "Charge",
            icon = DemoIcons.Charge,
            onClick = onCharge,
            enabled = !state.isWorking && state.isReady,
        )
        if (!state.isReady) {
            Caption("Turn on the terminal first. Charging needs a prepared reader.")
        }
    }
}

@Composable
private fun ActivityBlock(
    state: TapToPayUiState,
    onClearEvents: () -> Unit,
) {
    Block(title = "Activity") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionHeader(title = "Events")
            TextButton(onClick = onClearEvents, enabled = !state.events.isEmpty) {
                Text("Clear")
            }
        }
        if (state.events.isEmpty) {
            Caption("No activity yet")
        } else {
            state.events.entries.forEach { event ->
                EventRow(label = event.code.wireName, detail = event.detail)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivationSheet(
    state: TapToPayUiState,
    onActivationCodeChange: (String) -> Unit,
    onActivate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
        ) {
            SectionHeader(
                title = "Activate this device",
                note = "Enter the code Payabli issued for it.",
            )
            OutlinedTextField(
                value = state.activationCode,
                onValueChange = onActivationCodeChange,
                label = { Text("Activation code") },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        // Codes are issued in upper case, and autocorrect on a code is only ever
                        // a way to get a wrong one.
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            Caption(
                "Type ${DemoTerminalController.REJECTED_ACTIVATION_CODE} to see what a rejected code looks like.",
            )
            ProminentButton(
                text = "Activate",
                icon = DemoIcons.Activate,
                onClick = onActivate,
                enabled = state.activationCode.isNotBlank(),
            )
        }
    }
}

@Composable
private fun Block(
    title: String,
    note: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
        SectionHeader(title = title, note = note)
        content()
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@PreviewLightDark
@Composable
private fun TapToPayScreenPreview() {
    PreviewSurface {
        TapToPayScreen(
            state =
                TapToPayUiState(
                    configuration =
                        DemoConfiguration("test6", "com.payabli.example.app", "", DemoEnvironment.SANDBOX, true),
                    // Resolved, not typed out. A preview showing an address the resolver does not
                    // choose is a preview of a screen that cannot happen.
                    tokenServer =
                        TokenHostResolver.resolve(
                            launchOverride = null,
                            buildSettingHost = "",
                            isEmulator = true,
                            defaults = TokenHostDefaults.fromBuildConfig(),
                        ),
                    session = TerminalSessionState.Ready,
                    isReady = true,
                    resultText = "✓ Charge: demo-txn-0001",
                    events =
                        EventBuffer()
                            .add(TerminalEvent(TerminalEventCode.ReaderReady))
                            .add(TerminalEvent(TerminalEventCode.ChargeInitiated, "amount=1.00")),
                ),
            actions = TapToPayActions.none(),
        )
    }
}
