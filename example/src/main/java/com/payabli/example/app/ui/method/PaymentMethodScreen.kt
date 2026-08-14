package com.payabli.example.app.ui.method

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.payabli.example.app.flow.PaymentProgress
import com.payabli.example.app.flow.PaymentSteps
import com.payabli.example.app.payment.DemoForms
import com.payabli.example.app.ui.components.DemoIcons
import com.payabli.example.app.ui.components.EntersAfter
import com.payabli.example.app.ui.components.PreviewSurface
import com.payabli.example.app.ui.components.ProminentButton
import com.payabli.example.app.ui.components.SuccessMark
import com.payabli.example.app.ui.payment.PaymentFlowActions
import com.payabli.example.app.ui.payment.PaymentFlowScreen
import com.payabli.example.app.ui.theme.Dimens
import com.payabli.example.app.ui.theme.PayabliDemoTheme
import com.payabli.sdk.payin.payment.PayInSubmissionState

/** Store a card or bank account and get a reusable token back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentMethodScreen(
    state: PaymentMethodUiState,
    actions: PaymentFlowActions,
    modifier: Modifier = Modifier,
) {
    // One place reads the SDK's state: the flag below and the form both come from it.
    val submission =
        state.payments
            ?.state
            ?.collectAsState()
            ?.value ?: PayInSubmissionState.Idle

    PaymentFlowScreen(
        title = "Save a method",
        state = state,
        flow = state.payments,
        operation = state.operation,
        submission = submission,
        steps =
            PaymentSteps.forStoringMethod(
                PaymentProgress(
                    backendReachable = state.backendReachable,
                    backendChecked = state.tokenCheckText.isNotEmpty() && !state.isCheckingToken,
                    isCheckingBackend = state.isCheckingToken,
                    isSubmitting = submission is PayInSubmissionState.Submitting,
                    submitFailed = state.submitFailed,
                    finished = state.finished,
                ),
            ),
        resultEmptyText = "Nothing stored yet",
        startOverText = "Save another method",
        actions = actions,
        modifier = modifier,
    )
}

/** What a reader sees after a method is stored. One fact, said once. */
@Composable
fun PaymentMethodSavedScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { insets ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .padding(Dimens.ScreenPadding * 2),
            verticalArrangement = Arrangement.spacedBy(Dimens.SectionSpacing, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SuccessMark()
            // Staggered behind the mark, so the eye lands on the tick and the sentence arrives after
            // it. All three at once reads as a screen that was already there.
            EntersAfter(delayMillis = 120) {
                Text(
                    text = "Payment method saved",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            EntersAfter(delayMillis = 200) {
                Text(
                    text = "You can charge it later with the token that came back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            EntersAfter(delayMillis = 300) {
                ProminentButton(text = "Done", icon = DemoIcons.Pass, onClick = onDone)
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun PaymentMethodScreenPreview() {
    PreviewSurface {
        PaymentMethodScreen(
            state =
                PaymentMethodUiState(
                    setup = DemoForms.storePaymentMethod(),
                    resultText = "Stored method: demo-method-0001\nResponse: Payment method saved",
                    diagnostics = listOf("RESPONSE 1 paymentMethod\nreason=Success"),
                ),
            actions = PaymentFlowActions.none(),
        )
    }
}

@PreviewLightDark
@Composable
private fun PaymentMethodSavedScreenPreview() {
    PayabliDemoTheme {
        PaymentMethodSavedScreen(onDone = {})
    }
}
