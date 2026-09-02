package com.payabli.example.app.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.payabli.sdk.payin.PayabliPayInForm

/**
 * Where the SDK's payment form mounts.
 *
 * Both payment screens call it twice each, inline and inside a bottom sheet, and neither knows what
 * it renders. The app owns the call site, the configuration describing what the form should collect,
 * the result and error models, the sheet chrome and both outcome screens.
 *
 * **Nothing is passed about how it looks.** The form reads this app's `MaterialTheme`, so it arrives
 * in the Payabli palette here and in an integrator's palette there.
 *
 * **The SDK submits.** The payer's tap runs [operation] through [payments], and the outcome arrives on one of the
 * two callbacks. This app translates it into its own result and error types one layer up, so no screen below
 * holds an SDK outcome.
 */
@Composable
fun PaymentFormHost(
    setup: PayInFormSetup,
    payments: PayInFlowHandle,
    operation: PayInOperation,
    onCompleted: (PayInOutcome.Approved) -> Unit,
    onFailed: (PayInOutcome.Refused) -> Unit,
    modifier: Modifier = Modifier,
) {
    PayabliPayInForm(
        payIn =
            requireNotNull(payments.formTarget) {
                "PaymentFormHost draws a handle the SDK produced, and this one has no pay-in behind it"
            },
        operation = operation.operation,
        configuration = setup.configuration,
        modifier = modifier,
        labels = setup.labels,
        onCompleted = { onCompleted(it.toOutcome()) },
        onFailed = { onFailed(it.toOutcome()) },
        // Nothing here follows the instrument on screen: the form fills its own boxes and submits them, and
        // no screen in this app asks which tab the payer is on.
        onMethodChanged = {},
    )
}
