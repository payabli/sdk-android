package com.payabli.example.app.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.payabli.example.app.flow.PaymentSteps
import com.payabli.example.app.payment.DemoForms
import com.payabli.example.app.payment.PaymentResult
import com.payabli.example.app.payment.ResponseJson
import com.payabli.example.app.payment.Transaction
import com.payabli.example.app.payment.TransactionSummary
import com.payabli.example.app.ui.components.ContextLine
import com.payabli.example.app.ui.components.DemoIcons
import com.payabli.example.app.ui.components.DemoScreen
import com.payabli.example.app.ui.components.DetailRow
import com.payabli.example.app.ui.components.DiagnosticsPanel
import com.payabli.example.app.ui.components.PreviewSurface
import com.payabli.example.app.ui.components.ProminentButton
import com.payabli.example.app.ui.components.ResultCard
import com.payabli.example.app.ui.components.SectionHeader
import com.payabli.example.app.ui.components.SelectableMonospaceBlock
import com.payabli.example.app.ui.components.StepRow
import com.payabli.example.app.ui.components.SuccessMark
import com.payabli.example.app.ui.components.TokenCheckStep
import com.payabli.example.app.ui.payment.PaymentFormHost
import com.payabli.example.app.ui.theme.Dimens
import com.payabli.sdk.payin.form.PayInFormValues

/** Charge a card or bank account now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    state: CaptureUiState,
    onOpenSheet: () -> Unit,
    onDismissSheet: () -> Unit,
    onSubmit: (PayInFormValues) -> Unit,
    onCheckToken: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps =
        PaymentSteps.forCapture(
            backendReachable = state.backendReachable,
            backendChecked = state.tokenCheckText.isNotEmpty() && !state.isCheckingToken,
            isSubmitting = state.isSubmitting,
            submitFailed = state.submitFailed,
            finished = state.outcomeReady,
        )

    DemoScreen(title = "Capture", modifier = modifier) {
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
            ResultCard(text = state.resultText, emptyText = "No payment yet")
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

/**
 * The full transaction, as the API described it.
 *
 * The summary and the raw response together, because the summary is what a reader checks and the
 * response is what they send to support when the summary says something surprising.
 */
@Composable
fun CaptureResultScreen(
    result: PaymentResult?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DemoScreen(title = "Payment", modifier = modifier) {
        if (result == null) {
            Text(
                text = "No payment yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@DemoScreen
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SuccessMark(size = 36.dp)
            Spacer(Modifier.width(Dimens.CardPadding))
            Column {
                Text(
                    text = "Payment submitted",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = result.reason ?: result.explanation ?: result.code,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
            SectionHeader(title = "Summary")
            TransactionSummary.rows(result).forEach { row ->
                DetailRow(label = row.label, value = row.value)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
            SectionHeader(title = "Response", note = "Exactly what came back, with the keys sorted.")
            SelectableMonospaceBlock(text = ResponseJson.render(result.apiResponse))
        }

        ProminentButton(text = "Done", icon = DemoIcons.Pass, onClick = onDone)
    }
}

@PreviewLightDark
@Composable
private fun CaptureScreenPreview() {
    PreviewSurface {
        CaptureScreen(
            state =
                CaptureUiState(
                    setup = DemoForms.capture(),
                    resultText = "Code: 1\nReason: Approved\nTransaction: demo-txn-0001",
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
private fun CaptureResultScreenPreview() {
    PreviewSurface {
        CaptureResultScreen(
            result =
                PaymentResult(
                    code = "1",
                    reason = "Approved",
                    explanation = "The payment was authorised and captured.",
                    action = "None",
                    transaction =
                        Transaction(
                            paymentTransactionId = "demo-txn-0001",
                            gatewayTransactionId = "demo-gw-0001",
                            orderId = "demo-order-0001",
                            method = "card",
                            operation = "capture",
                            status = "Captured",
                            totalAmount = "1.10",
                            feeAmount = "0.10",
                            source = "android-example",
                        ),
                ),
            onDone = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun CaptureResultScreenEmptyPreview() {
    PreviewSurface {
        CaptureResultScreen(result = null, onDone = {})
    }
}
