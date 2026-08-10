package com.payabli.example.app.ui.method

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.payabli.example.app.flow.PaymentSteps
import com.payabli.example.app.payment.DemoForms
import com.payabli.example.app.ui.components.ContextLine
import com.payabli.example.app.ui.components.DemoIcons
import com.payabli.example.app.ui.components.DemoScreen
import com.payabli.example.app.ui.components.DiagnosticsPanel
import com.payabli.example.app.ui.components.EntersAfter
import com.payabli.example.app.ui.components.PreviewSurface
import com.payabli.example.app.ui.components.ProminentButton
import com.payabli.example.app.ui.components.ResultCard
import com.payabli.example.app.ui.components.SectionHeader
import com.payabli.example.app.ui.components.StepRow
import com.payabli.example.app.ui.components.SuccessMark
import com.payabli.example.app.ui.components.TokenCheckStep
import com.payabli.example.app.ui.payment.PaymentFormHost
import com.payabli.example.app.ui.theme.Dimens
import com.payabli.example.app.ui.theme.PayabliDemoTheme
import com.payabli.sdk.payin.form.PayInFormValues

/** Store a card or bank account and get a reusable token back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentMethodScreen(
    state: PaymentMethodUiState,
    onOpenSheet: () -> Unit,
    onDismissSheet: () -> Unit,
    onSubmit: (PayInFormValues) -> Unit,
    onCheckToken: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps =
        PaymentSteps.forStoringMethod(
            backendReachable = state.backendReachable,
            backendChecked = state.tokenCheckText.isNotEmpty() && !state.isCheckingToken,
            isSubmitting = state.isSubmitting,
            submitFailed = state.submitFailed,
            finished = state.outcomeReady,
        )

    DemoScreen(title = "Payment method", modifier = modifier) {
        ContextLine(entryPoint = state.entryPoint, host = state.host)

        SectionHeader(title = "Steps", note = "What the SDK needs, in the order it needs it.")

        StepRow(index = 1, step = steps[0]) {
            TokenCheckStep(
                text = state.tokenCheckText,
                isChecking = state.isCheckingToken,
                onCheck = onCheckToken,
            )
        }

        StepRow(index = 2, step = steps[1]) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
                ProminentButton(
                    text = "Open in a bottom sheet",
                    icon = DemoIcons.OpenSheet,
                    onClick = onOpenSheet,
                )
                PaymentFormHost(
                    setup = state.setup,
                    onSubmit = onSubmit,
                    isSubmitting = state.isSubmitting,
                )
            }
        }

        StepRow(index = 3, step = steps[2]) {
            ResultCard(text = state.resultText, emptyText = "Nothing stored yet")
        }

        DiagnosticsPanel(messages = state.diagnostics, isEnabled = state.diagnosticsEnabled)
    }

    if (state.isSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = onDismissSheet, sheetState = sheetState) {
            Column(modifier = Modifier.fillMaxWidth().padding(Dimens.ScreenPadding)) {
                PaymentFormHost(
                    setup = state.setup,
                    onSubmit = onSubmit,
                    isSubmitting = state.isSubmitting,
                )
            }
        }
    }
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
            onOpenSheet = {},
            onDismissSheet = {},
            onSubmit = {},
            onCheckToken = {},
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
