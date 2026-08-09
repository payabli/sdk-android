package com.payabli.example.app.ui.payment

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.payabli.example.app.payment.DemoFormSetup
import com.payabli.sdk.payin.ui.PayabliPayInForm

/**
 * Where the SDK's payment form mounts.
 *
 * Both payment screens call it twice each, inline and inside a bottom sheet, and neither knows what
 * it renders. The app owns the call site, the configuration describing what the form should collect,
 * the result and error models, the sheet chrome and both outcome screens.
 *
 * **Nothing is passed about how it looks.** The form reads this app's `MaterialTheme`, so it arrives
 * in the Payabli palette here and in an integrator's palette there. Passing a style would be the
 * thing to do only if this app wanted one form to differ from the rest of it.
 *
 * The form does not submit. It reports that the payer asked to, and this app's view model submits
 * through its own flow controller, which is where a result still comes from.
 */
@Composable
fun PaymentFormHost(
    setup: DemoFormSetup,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    isSubmitting: Boolean = false,
) {
    PayabliPayInForm(
        configuration = setup.configuration,
        labels = setup.labels,
        modifier = modifier,
        isSubmitting = isSubmitting,
        onSubmit = onSubmit,
    )
}
