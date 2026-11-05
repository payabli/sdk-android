package com.payabli.example.app.demo.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.payabli.example.app.demo.flow.PaymentProgress
import com.payabli.example.app.demo.flow.PaymentSteps
import com.payabli.example.app.demo.payment.PaymentResult
import com.payabli.example.app.demo.payment.Transaction
import com.payabli.example.app.demo.payment.TransactionSummary
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.example.app.demo.ui.components.DemoIcons
import com.payabli.example.app.demo.ui.components.DemoScreen
import com.payabli.example.app.demo.ui.components.DetailRow
import com.payabli.example.app.demo.ui.components.PreviewSurface
import com.payabli.example.app.demo.ui.components.ProminentButton
import com.payabli.example.app.demo.ui.components.SectionHeader
import com.payabli.example.app.demo.ui.components.SuccessMark
import com.payabli.example.app.demo.ui.payment.PaymentFlowActions
import com.payabli.example.app.demo.ui.payment.PaymentFlowScreen
import com.payabli.example.app.demo.ui.theme.Dimens
import com.payabli.example.app.sdk.PayInForms
import com.payabli.example.app.sdk.capturePayment
import java.math.BigDecimal

/** Charge a card or bank account now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    state: CaptureUiState,
    actions: PaymentFlowActions,
    modifier: Modifier = Modifier,
) {
    // One place asks whether a submission is in flight: the step list and the form's own gate both read it.
    val isSubmitting = state.payments?.isSubmitting() ?: false

    PaymentFlowScreen(
        title = "Capture a payment",
        state = state,
        payments = state.payments,
        operation = state.operation,
        isSubmitting = isSubmitting,
        steps =
            PaymentSteps.forCapture(
                PaymentProgress(
                    backendReachable = state.backendReachable,
                    backendChecked = state.tokenCheckText.isNotEmpty() && !state.isCheckingToken,
                    isCheckingBackend = state.isCheckingToken,
                    isSubmitting = formSubmitting(isSubmitting, state.isVoiding),
                    submitFailed = state.submitFailed,
                    finished = state.finished,
                ),
            ),
        resultEmptyText = "No payment yet",
        startOverText = "Take another payment",
        actions = actions,
        modifier = modifier,
    )
}

/**
 * Whether the form step reads as submitting. A void sets the SDK's flag as any submission does, but it
 * reverses the transaction on screen rather than submitting the form, so it leaves that step finished and
 * the transaction it is reversing in view.
 *
 * @param isSubmitting the SDK's flag.
 * @param isVoiding a void of the shown transaction is running.
 * @return whether the form step is in flight.
 */
internal fun formSubmitting(
    isSubmitting: Boolean,
    isVoiding: Boolean,
): Boolean = isSubmitting && !isVoiding

/**
 * The full transaction: its code and identifiers.
 */
@Composable
fun CaptureResultScreen(
    result: PaymentResult?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DemoScreen(title = "Payment Response", modifier = modifier) {
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
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing)) {
            SectionHeader(title = "Summary")
            TransactionSummary.rows(result).forEach { row ->
                DetailRow(label = row.label, value = row.value)
            }
        }

        ProminentButton(text = "Done", icon = DemoIcons.Pass, onClick = onDone)
    }
}

@PreviewLightDark
@Composable
private fun CaptureScreenPreview() {
    // Fixed, where a run randomizes: a preview that renders a different figure each time is a preview that
    // cannot be compared to the last one.
    val amount = BigDecimal("1.10")
    val identity = SampleIdentity.from("Google Pixel 7a")
    PreviewSurface {
        CaptureScreen(
            state =
                CaptureUiState(
                    setup = PayInForms.capture(),
                    amount = amount,
                    sampleIdentity = identity,
                    operation =
                        capturePayment(
                            paymentReference = "preview",
                            amount = amount,
                            identity = identity,
                            atMillis = 0,
                            suppliesDemoCustomer = true,
                        ),
                    resultText = "✓ Code: 1\nPayment transaction: demo-txn-0001",
                ),
            actions = PaymentFlowActions.none(),
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
