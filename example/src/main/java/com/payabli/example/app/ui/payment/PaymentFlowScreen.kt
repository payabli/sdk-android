package com.payabli.example.app.ui.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.payabli.example.app.flow.FlowStep
import com.payabli.example.app.flow.StepStatus
import com.payabli.example.app.payment.DemoFormSetup
import com.payabli.example.app.ui.components.ContextLine
import com.payabli.example.app.ui.components.DemoIcons
import com.payabli.example.app.ui.components.DemoScreen
import com.payabli.example.app.ui.components.DiagnosticsPanel
import com.payabli.example.app.ui.components.ProminentButton
import com.payabli.example.app.ui.components.ResultCard
import com.payabli.example.app.ui.components.SectionHeader
import com.payabli.example.app.ui.components.StepRow
import com.payabli.example.app.ui.components.TokenCheckStep
import com.payabli.example.app.ui.theme.Dimens
import com.payabli.sdk.payin.form.PayInFormValues

/**
 * What the two card-not-present screens have in common, which is everything but their wording.
 *
 * Written twice, the two were free to drift on what a step said or when it unlocked.
 */
interface PaymentFlowUiState {
    val setup: DemoFormSetup
    val resultText: String
    val tokenCheckText: String
    val isCheckingToken: Boolean
    val isSubmitting: Boolean
    val entryPoint: String
    val host: String
    val diagnostics: List<String>
    val diagnosticsEnabled: Boolean
    val isSheetOpen: Boolean
}

/** What a payment screen can be asked to do. */
data class PaymentFlowActions(
    val onCheckToken: () -> Unit,
    val onOpenSheet: () -> Unit,
    val onDismissSheet: () -> Unit,
    val onSubmit: (PayInFormValues) -> Unit,
) {
    companion object {
        /** For a preview, which renders the screen and drives nothing. */
        fun none(): PaymentFlowActions = PaymentFlowActions({}, {}, {}, {})
    }
}

/**
 * The sequence both card-not-present screens show.
 *
 * @param steps the three, already derived. The screen renders them and decides nothing.
 * @param resultEmptyText what the last step says before anything has come back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentFlowScreen(
    title: String,
    state: PaymentFlowUiState,
    steps: List<FlowStep>,
    resultEmptyText: String,
    actions: PaymentFlowActions,
    modifier: Modifier = Modifier,
) {
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
                    text = "Open in a bottom sheet",
                    icon = DemoIcons.OpenSheet,
                    onClick = actions.onOpenSheet,
                    // A second, empty form beside a submission already in flight.
                    enabled = !state.isSubmitting,
                )
                PaymentFormHost(
                    setup = state.setup,
                    onSubmit = actions.onSubmit,
                    isSubmitting = state.isSubmitting,
                )
            }
        }

        StepRow(index = 3, step = steps[2]) {
            ResultCard(text = state.resultText, emptyText = resultEmptyText)
        }

        DiagnosticsPanel(messages = state.diagnostics, isEnabled = state.diagnosticsEnabled)
    }

    if (state.isSheetOpen) {
        // Both halves, because a swipe and a back press take different routes to the same place:
        // the form holds what was typed in `remember`, and dismissing disposes it mid-submission.
        //
        // The callback has to keep its identity. `rememberSheetState` passes it to `rememberSaveable`
        // as a key, so a lambda capturing the state snapshot is a new key whenever the state changes,
        // and the sheet is rebuilt from `Hidden` at the moment a submission starts. It reads the flag
        // through `rememberUpdatedState` instead, which is a stable holder of a changing value.
        val submitting = rememberUpdatedState(state.isSubmitting)
        val holdWhileSubmitting =
            remember { { value: SheetValue -> !submitting.value || value != SheetValue.Hidden } }
        val sheetState =
            rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = holdWhileSubmitting,
            )
        ModalBottomSheet(
            onDismissRequest = { if (!state.isSubmitting) actions.onDismissSheet() },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(Dimens.ScreenPadding)) {
                PaymentFormHost(
                    setup = state.setup,
                    onSubmit = actions.onSubmit,
                    isSubmitting = state.isSubmitting,
                )
            }
        }
    }
}
