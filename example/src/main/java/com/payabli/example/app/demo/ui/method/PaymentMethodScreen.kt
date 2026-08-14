package com.payabli.example.app.demo.ui.method

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.payabli.example.app.demo.flow.PaymentProgress
import com.payabli.example.app.demo.flow.PaymentSteps
import com.payabli.example.app.demo.ui.components.DemoIcons
import com.payabli.example.app.demo.ui.components.EntersAfter
import com.payabli.example.app.demo.ui.components.PreviewSurface
import com.payabli.example.app.demo.ui.components.ProminentButton
import com.payabli.example.app.demo.ui.components.SuccessMark
import com.payabli.example.app.demo.ui.payment.PaymentFlowActions
import com.payabli.example.app.demo.ui.payment.PaymentFlowScreen
import com.payabli.example.app.demo.ui.theme.Dimens
import com.payabli.example.app.demo.ui.theme.PayabliDemoTheme
import com.payabli.example.app.sdk.PayInForms

/** Store a card or bank account and get a reusable token back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentMethodScreen(
    state: PaymentMethodUiState,
    actions: PaymentFlowActions,
    modifier: Modifier = Modifier,
) {
    // One place asks whether a submission is in flight: the step list and the form's own gate both read it.
    val isSubmitting = state.payments?.isSubmitting() ?: false

    PaymentFlowScreen(
        title = "Save a method",
        state = state,
        flow = state.payments,
        operation = state.operation,
        isSubmitting = isSubmitting,
        steps =
            PaymentSteps.forStoringMethod(
                PaymentProgress(
                    backendReachable = state.backendReachable,
                    backendChecked = state.tokenCheckText.isNotEmpty() && !state.isCheckingToken,
                    isCheckingBackend = state.isCheckingToken,
                    isSubmitting = isSubmitting,
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
                    setup = PayInForms.storePaymentMethod(),
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
