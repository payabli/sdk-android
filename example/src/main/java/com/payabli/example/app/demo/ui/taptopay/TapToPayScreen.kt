package com.payabli.example.app.demo.ui.taptopay

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
import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.config.TokenHostDefaults
import com.payabli.example.app.demo.config.TokenHostResolver
import com.payabli.example.app.demo.flow.FlowStep
import com.payabli.example.app.demo.flow.StepStatus
import com.payabli.example.app.demo.flow.TerminalSteps
import com.payabli.example.app.demo.terminal.EventBuffer
import com.payabli.example.app.demo.terminal.TerminalEvent
import com.payabli.example.app.demo.terminal.TerminalEventCode
import com.payabli.example.app.demo.terminal.TerminalFailureReason
import com.payabli.example.app.demo.terminal.TerminalSessionState
import com.payabli.example.app.demo.terminal.chipSpecFor
import com.payabli.example.app.demo.terminal.sessionFailureReason
import com.payabli.example.app.demo.ui.components.BorderedButton
import com.payabli.example.app.demo.ui.components.ContextLine
import com.payabli.example.app.demo.ui.components.DemoIcons
import com.payabli.example.app.demo.ui.components.DemoScreen
import com.payabli.example.app.demo.ui.components.EventRow
import com.payabli.example.app.demo.ui.components.PreviewSurface
import com.payabli.example.app.demo.ui.components.ProminentButton
import com.payabli.example.app.demo.ui.components.ReadinessCard
import com.payabli.example.app.demo.ui.components.RecheckWhenFocused
import com.payabli.example.app.demo.ui.components.ResultCard
import com.payabli.example.app.demo.ui.components.SectionHeader
import com.payabli.example.app.demo.ui.components.StateChip
import com.payabli.example.app.demo.ui.components.StepRow
import com.payabli.example.app.demo.ui.components.TokenCheckStep
import com.payabli.example.app.demo.ui.theme.Dimens
import com.payabli.example.app.sdk.DemoEnvironment

/**
 * Take a contactless payment on this device.
 */
@Composable
fun TapToPayScreen(
    state: TapToPayUiState,
    actions: TapToPayActions,
    modifier: Modifier = Modifier,
) {
    // Step 1 stops offering its own recheck as soon as it reads Done, which is exactly the state
    // this protects.
    RecheckWhenFocused(actions.onRecheck)

    val steps =
        TerminalSteps.forCharging(
            readiness = state.readiness,
            session = state.session,
            activationFailed = state.activationFailure != null,
            chargeFailed = state.chargeFailure != null,
            working = state.workingAction,
            activated = state.activated,
            readerDenied = state.failureReason == TerminalFailureReason.DeviceIneligible,
        )

    DemoScreen(
        title = "Tap to Pay",
        modifier = modifier,
        actions = {
            StateChip(
                spec = chipSpecFor(state.session),
                modifier = Modifier.padding(end = Dimens.CardPadding),
            )
        },
    ) {
        ContextLine(
            entryPoint = state.configuration.entryPoint,
            host = state.configuration.environment.host,
        )

        SectionHeader(title = "Steps", note = "What the SDK needs, in the order it needs it.")

        StepRow(index = 1, step = steps[0]) {
            ReadinessCard(
                readiness = state.readiness,
                problems = state.problems,
                onRecheck = actions.onRecheck,
            )
        }

        StepRow(index = 2, step = steps[1]) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                FailureReason(steps[1], sessionFailureReason(state.session, state.failureReason))
                ProminentButton(
                    text = "Set up the terminal",
                    icon = DemoIcons.TapToPay,
                    onClick = actions.onEnable,
                    enabled = !state.isWorking,
                )
                BorderedButton(
                    text = "Start the session again",
                    icon = DemoIcons.Reinitialize,
                    onClick = actions.onReinitialize,
                    enabled = !state.isWorking,
                )
                TokenCheckStep(
                    text = state.tokenProbeText,
                    isChecking = state.isProbingToken,
                    onCheck = actions.onProbeToken,
                    enabled = !state.isWorking,
                )
            }
        }

        StepRow(index = 3, step = steps[2]) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                FailureReason(steps[2], state.activationFailure.orEmpty())
                BorderedButton(
                    text = "Activate this device",
                    icon = DemoIcons.Activate,
                    onClick = actions.onOpenActivation,
                    enabled = !state.isWorking,
                )
                Caption("Payabli issues the code.")
            }
        }

        StepRow(index = 4, step = steps[3]) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                // A denied reader fails this step with no charge attempted, so there is no recorded
                // charge failure to show.
                FailureReason(
                    steps[3],
                    state.chargeFailure ?: state.failureReason?.message.orEmpty(),
                )
                PaymentBlock(state, actions.onAmountChange, actions.onCharge)
            }
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

/** The reason a step failed, beside the controls that retry it. */
@Composable
private fun FailureReason(
    step: FlowStep,
    text: String,
) {
    if (step.status == StepStatus.Failed && text.isNotEmpty()) {
        ResultCard(text = text, emptyText = "")
    }
}

@Composable
private fun PaymentBlock(
    state: TapToPayUiState,
    onAmountChange: (String) -> Unit,
    onCharge: () -> Unit,
) {
    // No heading. The step above is titled "Take a payment", and a section header here repeats it.
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
        OutlinedTextField(
            value = state.amountText,
            onValueChange = onAmountChange,
            label = { Text("Amount") },
            prefix = { Text("$") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            // A working step keeps its controls, disabled. Left editable, the amount on screen can
            // stop matching the one being charged.
            enabled = !state.isWorking,
            modifier = Modifier.fillMaxWidth(),
        )
        ProminentButton(
            text = "Charge",
            icon = DemoIcons.Charge,
            onClick = onCharge,
            enabled = !state.isWorking && state.isReady,
        )
        if (!state.isReady) {
            Caption("Set up the terminal first. Charging needs a prepared reader.")
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
            Caption("Six digits, issued to the merchant out of band.")
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
