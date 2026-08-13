package com.payabli.example.app.ui.payment

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
import com.payabli.example.app.flow.FlowStep
import com.payabli.example.app.flow.StepStatus
import com.payabli.example.app.payment.DemoFormSetup
import com.payabli.example.app.payment.DemoPrefill
import com.payabli.example.app.ui.components.BorderedButton
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
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.payment.PayabliPayInPaymentFlow

/**
 * What the two card-not-present screens have in common, which is everything but their wording.
 *
 * Written twice, the two were free to drift on what a step said or when it unlocked.
 */
interface PaymentFlowUiState {
    val setup: DemoFormSetup
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

    /** Offers the button that fills the form with [com.payabli.example.app.payment.DemoPrefill]'s values. */
    val prefillEnabled: Boolean
}

/** What a payment screen can be asked to do. */
data class PaymentFlowActions(
    val onCheckToken: () -> Unit,
    val onOpenSheet: () -> Unit,
    val onDismissSheet: () -> Unit,
    val onCompleted: (PayInSubmissionState.Succeeded) -> Unit,
    val onFailed: (PayInSubmissionState.Failed) -> Unit,
) {
    companion object {
        /** For a preview, which renders the screen and drives nothing. */
        fun none(): PaymentFlowActions = PaymentFlowActions({}, {}, {}, {}, {})
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
    flow: PayabliPayInPaymentFlow?,
    operation: PayabliPayInOperation,
    submission: PayInSubmissionState,
    steps: List<FlowStep>,
    resultEmptyText: String,
    actions: PaymentFlowActions,
    modifier: Modifier = Modifier,
) {
    val isSubmitting = submission is PayInSubmissionState.Submitting

    // The screen's own, not the app's: it exists to save typing during a QA run, and no screen below reads it.
    var prefilled by remember { mutableStateOf<PayInFormValues?>(null) }

    // Bumped on every tap and used as the form's `key`. `initialValues` is compared by value, so seeding the
    // same set twice is not a change and the form keeps what the payer has since edited: the button then does
    // nothing on its second tap. A new key composes a new form, which starts from the seed again.
    var prefills by remember { mutableIntStateOf(0) }

    // Which instrument the form is on, which the form reports whenever the payer switches tabs. The card and
    // the bank account take different fields, so the button has to fill the one on screen.
    var method by remember { mutableStateOf(state.setup.configuration.startingMethod) }
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
                            prefilled = DemoPrefill.valuesFor(method)
                            prefills++
                        },
                        enabled = !isSubmitting,
                        contentColor = MaterialTheme.colorScheme.tertiary,
                    )
                }
                // Only once the session exists. Until then the step above is what the screen offers.
                flow?.let { payments ->
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
    }
}
