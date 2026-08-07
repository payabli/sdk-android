package com.payabli.example.app.ui.payment

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.payabli.example.app.payment.PaymentError
import com.payabli.example.app.payment.PaymentFormConfiguration

/**
 * The seam. The only file in the app that knows whether the payment form is real.
 *
 * Both payment screens call it twice each, inline and inside a bottom sheet, and neither knows what
 * it renders. The app owns the call site, the configuration describing what the form should show,
 * the result and error models, the sheet chrome and both outcome screens. The form's interior belongs
 * to the SDK and is not built here: it would be thrown away the day the SDK's component lands.
 *
 * When that day comes, the body below becomes a call to the SDK's composable plus two mapping
 * functions onto [PaymentResult] and [PaymentError]. Nothing else in the app moves.
 *
 */
@Composable
fun PaymentFormHost(
    configuration: PaymentFormConfiguration,
    onSubmit: () -> Unit,
    onError: (PaymentError) -> Unit,
    modifier: Modifier = Modifier,
    isSubmitting: Boolean = false,
) {
    PaymentFormPlaceholder(
        configuration = configuration,
        onSubmit = onSubmit,
        onError = onError,
        modifier = modifier,
        isSubmitting = isSubmitting,
    )
}
