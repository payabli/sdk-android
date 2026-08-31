package com.payabli.example.app.demo.ui.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.payabli.example.app.BuildConfig
import com.payabli.example.app.demo.flow.FlowStep
import com.payabli.example.app.demo.flow.StepStatus
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.demo.ui.components.BorderedButton
import com.payabli.example.app.demo.ui.components.ContextLine
import com.payabli.example.app.demo.ui.components.DemoIcons
import com.payabli.example.app.demo.ui.components.DemoScreen
import com.payabli.example.app.demo.ui.components.DiagnosticsPanel
import com.payabli.example.app.demo.ui.components.ProminentButton
import com.payabli.example.app.demo.ui.components.ResultCard
import com.payabli.example.app.demo.ui.components.SectionHeader
import com.payabli.example.app.demo.ui.components.StepRow
import com.payabli.example.app.demo.ui.components.TokenCheckStep
import com.payabli.example.app.demo.ui.theme.Dimens
import com.payabli.example.app.sdk.PayInFlowHandle
import com.payabli.example.app.sdk.PayInFormSeed
import com.payabli.example.app.sdk.PayInFormSetup
import com.payabli.example.app.sdk.PayInMethod
import com.payabli.example.app.sdk.PayInOperation
import com.payabli.example.app.sdk.PayInOutcome
import com.payabli.example.app.sdk.PayInPrefill
import com.payabli.example.app.sdk.PaymentFormHost

/**
 * What the two card-not-present screens have in common, which is everything but their wording.
 *
 * Written twice, the two were free to drift on what a step said or when it unlocked.
 */
interface PaymentFlowUiState {
    val setup: PayInFormSetup
    val resultText: String

    /**
     * A result arrived and this screen still holds it.
     *
     * Read from the payload rather than from the signal that pushed the outcome screen: that signal is cleared
     * as soon as navigation consumes it, which left the last step reading "waiting" over a completed payment.
     */
    val finished: Boolean
    val tokenCheckText: String
    val isCheckingToken: Boolean
    val entryPoint: String
    val host: String
    val diagnostics: List<String>
    val diagnosticsEnabled: Boolean
    val isSheetOpen: Boolean

    /** Offers the button that fills the form with [PayInPrefill]'s values. */
    val prefillEnabled: Boolean

    /** The device the prefill fills the form as. */
    val sampleIdentity: SampleIdentity
}

/** What a payment screen can be asked to do. */
data class PaymentFlowActions(
    val onCheckToken: () -> Unit,
    val onOpenSheet: () -> Unit,
    val onDismissSheet: () -> Unit,
    val onCompleted: (PayInOutcome.Approved) -> Unit,
    val onFailed: (PayInOutcome.Refused) -> Unit,
    /** Hands the screen back to the form step for another one. */
    val onStartOver: () -> Unit,
) {
    companion object {
        /** For a preview, which renders the screen and drives nothing. */
        fun none(): PaymentFlowActions = PaymentFlowActions({}, {}, {}, {}, {}, {})
    }
}

/**
 * The sequence both card-not-present screens show.
 *
 * @param steps the three, already derived. The screen renders them and decides nothing.
 * @param resultEmptyText what the last step says before anything has come back.
 * @param startOverText the control that hands the screen back to the form step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentFlowScreen(
    title: String,
    state: PaymentFlowUiState,
    flow: PayInFlowHandle?,
    operation: PayInOperation,
    isSubmitting: Boolean,
    steps: List<FlowStep>,
    resultEmptyText: String,
    startOverText: String,
    actions: PaymentFlowActions,
    modifier: Modifier = Modifier,
    // Above the form, in the sheet as well as inline. The form's own summary reads back the fields the SDK
    // knows, and what a payer is charged is not one of them, so a screen with a figure to add supplies it.
    // Above rather than below because the form's last child is its submit button: under it, the figure sits
    // past the control it qualifies and a payer can submit without having scrolled to it.
    formHeader: @Composable () -> Unit = {},
) {
    // The screen's own, not the app's: it exists to save typing during a demo run, and no screen below reads it.
    var prefilled by remember { mutableStateOf<PayInFormSeed?>(null) }

    // Bumped on every tap and used as the form's `key`. `initialValues` is compared by value, so seeding the
    // same set twice is not a change and the form keeps what the payer has since edited: the button then does
    // nothing on its second tap. A new key composes a new form, which starts from the seed again.
    var prefills by remember { mutableIntStateOf(0) }

    // Which instrument the form is on, which the form reports whenever the payer switches tabs. The card and
    // the bank account take different fields, so the button has to fill the one on screen.
    var method by remember { mutableStateOf(state.setup.startingMethod) }
    val offersPrefill = BuildConfig.DEBUG && state.prefillEnabled

    DemoScreen(title = title, modifier = modifier) {
        ContextLine(entryPoint = state.entryPoint, host = state.host)

        SectionHeader(title = "Steps", note = "What the SDK needs, in the order it needs it.")

        StepRow(index = 1, step = steps[0]) {
            TokenCheckStep(
                text = state.tokenCheckText,
                isChecking = state.isCheckingToken,
                onCheck = actions.onCheckToken,
            )
        }

        StepRow(index = 2, step = steps[1]) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                // A failure here blocks the result step, which is the only other place the text
                // appears, so without this the retry is offered with no reason beside it.
                if (steps[1].status == StepStatus.Failed) {
                    ResultCard(text = state.resultText, emptyText = resultEmptyText)
                }
                ProminentButton(
                    text = "Open as a sheet instead",
                    icon = DemoIcons.OpenSheet,
                    onClick = actions.onOpenSheet,
                    // A second, empty form beside a submission already in flight.
                    enabled = !isSubmitting,
                )
                if (offersPrefill) {
                    BorderedButton(
                        text = "Prefill test data (Debug)",
                        icon = DemoIcons.Prefill,
                        onClick = {
                            prefilled = PayInPrefill.valuesFor(method, state.sampleIdentity)
                            prefills++
                        },
                        enabled = !isSubmitting,
                        contentColor = MaterialTheme.colorScheme.tertiary,
                    )
                }
                // Only once the session exists. Until then the step above is what the screen offers.
                flow?.let { payments ->
                    formHeader()
                    key(prefills) {
                        PaymentFormHost(
                            setup = state.setup,
                            flow = payments,
                            operation = operation,
                            initialValues = prefilled,
                            onCompleted = actions.onCompleted,
                            onFailed = actions.onFailed,
                            onMethodChanged = { method = it },
                        )
                    }
                }
            }
        }

        StepRow(index = 3, step = steps[2]) {
            ResultCard(text = state.resultText, emptyText = resultEmptyText)
            // A finished step draws no controls, so a completed submit takes the form off the screen. This is
            // the way back to it.
            if (state.finished) {
                BorderedButton(
                    text = startOverText,
                    icon = DemoIcons.StartOver,
                    onClick = actions.onStartOver,
                )
            }
        }

        DiagnosticsPanel(messages = state.diagnostics, isEnabled = state.diagnosticsEnabled)
    }

    if (state.isSheetOpen) {
        FormSheet(
            state = state,
            actions = actions,
            flow = flow,
            operation = operation,
            initialValues = prefilled,
            formKey = prefills,
            isSubmitting = isSubmitting,
            onMethodChanged = { method = it },
            formHeader = formHeader,
        )
    }
}

/**
 * The same form, over the screen.
 *
 * A host mounts one of these beside the inline form, and both submit through the one flow the screen holds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormSheet(
    state: PaymentFlowUiState,
    actions: PaymentFlowActions,
    flow: PayInFlowHandle?,
    operation: PayInOperation,
    initialValues: PayInFormSeed?,
    formKey: Int,
    isSubmitting: Boolean,
    onMethodChanged: (PayInMethod) -> Unit,
    formHeader: @Composable () -> Unit,
) {
    // Both halves, because a swipe and a back press take different routes to the same place:
    // the form holds what was typed in `remember`, and dismissing disposes it mid-submission.
    //
    // The callback has to keep its identity. `rememberSheetState` passes it to `rememberSaveable`
    // as a key, so a lambda capturing the state snapshot is a new key whenever the state changes,
    // and the sheet is rebuilt from `Hidden` at the moment a submission starts. It reads the flag
    // through `rememberUpdatedState` instead, which is a stable holder of a changing value.
    val submitting = rememberUpdatedState(isSubmitting)
    val holdWhileSubmitting =
        remember { { value: SheetValue -> !submitting.value || value != SheetValue.Hidden } }
    val sheetState =
        rememberModalBottomSheetState(
            // Opens half height and expands, which is where the payer's thumb is. The hold below refuses
            // a dismiss mid-submission at either height.
            skipPartiallyExpanded = false,
            confirmValueChange = holdWhileSubmitting,
        )
    ModalBottomSheet(
        onDismissRequest = { if (!isSubmitting) actions.onDismissSheet() },
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // The sheet's own insets cover the system bars and not the keyboard.
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(Dimens.ScreenPadding),
        ) {
            // Only once the session exists. Until then the step above is what the screen offers.
            flow?.let { payments ->
                formHeader()
                key(formKey) {
                    PaymentFormHost(
                        setup = state.setup,
                        flow = payments,
                        operation = operation,
                        initialValues = initialValues,
                        onCompleted = actions.onCompleted,
                        onFailed = actions.onFailed,
                        onMethodChanged = onMethodChanged,
                    )
                }
            }
        }
    }
}
