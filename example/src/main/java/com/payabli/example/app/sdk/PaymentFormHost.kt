package com.payabli.example.app.sdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.payabli.sdk.payin.PayabliPayInForm
import com.payabli.sdk.payin.form.PayInMethodType

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
    method: FormMethodChoice,
    modifier: Modifier = Modifier,
) {
    val configuration =
        setup.configuration.let { configuration ->
            method.last
                ?.takeIf { it in configuration.methodsOffered }
                ?.let { configuration.copy(defaultMethod = it) }
                ?: configuration
        }
    PayabliPayInForm(
        payIn =
            requireNotNull(payments.formTarget) {
                "PaymentFormHost draws a handle the SDK produced, and this one has no pay-in behind it"
            },
        operation = operation.operation,
        configuration = configuration,
        modifier = modifier,
        labels = setup.labels,
        // Delivery closes the busy window before the app's own handling runs, so a recheck cannot
        // replace the flow between the two.
        onCompleted = {
            payments.outcomeTaken()
            onCompleted(it.toOutcome())
        },
        onFailed = {
            payments.outcomeTaken()
            onFailed(it.toOutcome())
        },
        onMethodChanged = { method.last = it },
    )
}

/**
 * The method the payer last chose in either of a screen's forms. The inline form and the sheet are two forms, each
 * opening on its own method, and what was entered under another method is dropped, so the one that opens first
 * starts on this one.
 */
@Stable
class FormMethodChoice internal constructor() {
    internal var last: PayInMethodType? by mutableStateOf(null)
}

/** @return a [FormMethodChoice] the screen's inline form and sheet share. */
@Composable
fun rememberFormMethodChoice(): FormMethodChoice = remember { FormMethodChoice() }
