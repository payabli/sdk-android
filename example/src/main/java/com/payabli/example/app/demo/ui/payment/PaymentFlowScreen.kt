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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
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
import com.payabli.example.app.sdk.PayInFormSetup
import com.payabli.example.app.sdk.PayInOperation
import com.payabli.example.app.sdk.PayInOutcome
import com.payabli.example.app.sdk.PaymentFormHost
import com.payabli.example.app.sdk.fillTestData

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

    /** Offers the button that fills the form with test values. */
    val prefillEnabled: Boolean

    /** The device the prefill fills the form as. */
    val sampleIdentity: SampleIdentity

    /**
     * The transaction a void would name, or null where there is none to reverse.
     *
     * Defaulted, because storing a payment method produces no transaction and that screen has nothing to
     * answer here.
     */
    val voidableTransactionId: String? get() = null

    /** A void is in flight. Never true on a screen that offers none. */
    val isVoiding: Boolean get() = false
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
    /**
     * Reverses what the last result names, for a screen that has something to reverse.
     *
     * Null on a screen where the question does not arise: storing a payment method produces no transaction,
     * so that screen supplies none and draws no control.
     */
    val onVoid: (() -> Unit)? = null,
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
    payments: PayInFlowHandle?,
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
                    PrefillButton(
                        identity = state.sampleIdentity,
                        enabled = !isSubmitting && payments != null,
                    )
                }
                // Only once the session exists. Until then the step above is what the screen offers.
                payments?.let { handle ->
                    formHeader()
                    PaymentFormHost(
                        setup = state.setup,
                        payments = handle,
                        operation = operation,
                        onCompleted = actions.onCompleted,
                        onFailed = actions.onFailed,
                    )
                }
            }
        }

        StepRow(index = 3, step = steps[2]) {
            ResultCard(text = state.resultText, emptyText = resultEmptyText)
            // Above Take another payment, because it acts on the transaction this step is describing while
            // that one leaves it behind. Drawn only where there is something to reverse: a screen that stores
            // a method supplies no action, and a reversed transaction stops offering it.
            val onVoid = actions.onVoid
            if (onVoid != null && state.voidableTransactionId != null) {
                BorderedButton(
                    text = if (state.isVoiding) "Voiding…" else "Void this transaction",
                    icon = DemoIcons.Void,
                    onClick = onVoid,
                    enabled = !state.isVoiding,
                )
            }
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
            payments = payments,
            operation = operation,
            isSubmitting = isSubmitting,
            offersPrefill = offersPrefill,
            formHeader = formHeader,
        )
    }
}

/**
 * The same form, over the screen.
 *
 * A host mounts one of these beside the inline form, and both submit through the one `PayabliPayIn` the
 * screen holds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormSheet(
    state: PaymentFlowUiState,
    actions: PaymentFlowActions,
    payments: PayInFlowHandle?,
    operation: PayInOperation,
    isSubmitting: Boolean,
    offersPrefill: Boolean,
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
            payments?.let { handle ->
                // Without this the sheet has no prefill at all: the screen's button is behind it, and the
                // fill reaches the composition it is drawn in.
                if (offersPrefill) {
                    PrefillButton(identity = state.sampleIdentity, enabled = !isSubmitting)
                }
                formHeader()
                PaymentFormHost(
                    setup = state.setup,
                    payments = handle,
                    operation = operation,
                    onCompleted = actions.onCompleted,
                    onFailed = actions.onFailed,
                )
            }
        }
    }
}

/**
 * Fills the form's boxes, so a demo run is one tap instead of eight fields. Debug builds only.
 *
 * It fills the composition it is drawn in, which is why the sheet needs one of these of its own.
 *
 * The expiry and the account type are pickers rather than boxes and are chosen by hand afterwards.
 */
@Composable
private fun PrefillButton(
    identity: SampleIdentity,
    enabled: Boolean,
) {
    val view = LocalView.current
    BorderedButton(
        text = "Prefill test data (Debug)",
        icon = DemoIcons.Prefill,
        onClick = { fillTestData(view, identity) },
        enabled = enabled,
        contentColor = MaterialTheme.colorScheme.tertiary,
    )
}
