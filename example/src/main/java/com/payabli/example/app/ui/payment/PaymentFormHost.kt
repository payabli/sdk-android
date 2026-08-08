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
 * **What moves when it lands, stated honestly.** [onSubmit] carries no value because nothing here
 * produces one: the form signals intent, the screen's view model submits through its own
 * [com.payabli.example.app.payment.PaymentFlowController], and that is where a [PaymentResult] comes
 * from today. An SDK component that owns submission returns the result itself, so this signature
 * gains a completion callback and the two view models lose their submit path. That callback is not
 * declared in advance: a parameter nothing invokes is indistinguishable from one that is wired, and
 * its shape is the SDK's to decide.
 *
 * So the swap is this file's body plus a mapping onto [PaymentResult] and [PaymentError], and, if the
 * component submits, this signature and the two call sites. Everything else in the app is unaffected
 * either way.
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
