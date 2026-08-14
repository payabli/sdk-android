package com.payabli.example.app.ui.payment

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.payabli.example.app.payment.DemoFormSetup
import com.payabli.sdk.payin.PayabliPayInForm
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.payment.PayabliPayInPaymentFlow

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
 * **The SDK submits.** The payer's tap runs [operation] through [flow], and the outcome arrives on one of the
 * two callbacks. This app translates it into its own result and error types one layer up, so no screen below
 * holds an SDK outcome.
 */
@Composable
fun PaymentFormHost(
    setup: DemoFormSetup,
    flow: PayabliPayInPaymentFlow,
    operation: PayabliPayInOperation,
    onCompleted: (PayInSubmissionState.Succeeded) -> Unit,
    onFailed: (PayInSubmissionState.Failed) -> Unit,
    modifier: Modifier = Modifier,
    initialValues: PayInFormValues? = null,
    onMethodChanged: (PayInMethodType) -> Unit = {},
) {
    PayabliPayInForm(
        flow = flow,
        operation = operation,
        configuration = setup.configuration,
        modifier = modifier,
        labels = setup.labels,
        initialValues = initialValues,
        onCompleted = onCompleted,
        onFailed = onFailed,
        onMethodChanged = onMethodChanged,
    )
}
