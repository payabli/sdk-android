package com.payabli.sdk.payin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormStyle
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.payment.PayabliPayInPaymentFlow
import com.payabli.sdk.payin.payment.narrowingKey
import com.payabli.sdk.payin.payment.offering
import com.payabli.sdk.payin.ui.PayInFormContent

/**
 * The Payabli payment form.
 *
 * Collects a card or a bank account and submits it. The payer's tap runs [operation] through [flow], and one
 * of [onCompleted] or [onFailed] fires with the outcome.
 *
 * **It looks like the app it is in.** With no [style] it takes its colors, type and shapes from the host's
 * `MaterialTheme`, so light, dark and dynamic color arrive with nothing passed. Use
 * `PayInFormStyleOverrides` to change one value, or `LocalPayInFormStyle` for every form in a tree.
 *
 * **When the service rejects a field, that field is marked and the instrument is emptied.** The card number,
 * expiry, security code, routing number and account number go on any outcome, because the instrument was
 * submitted either way. What identifies the payer stays, so correcting one field is not a second round of
 * typing.
 *
 * **Nothing in a failure is loggable.** [onFailed] carries the failure so a host can show it or navigate away
 * from it. `PayabliException.reason` and `detail` are displayable only: some of them can quote what was
 * submitted, so a record of a failure takes the exception's code, not its text.
 *
 * **Consumes no window insets.** Give it a scrolling viewport that accounts for the keyboard.
 *
 * **Retention is [flow]'s, not this composable's.** Hold the flow in something that survives a configuration
 * change, and a rotation keeps both the outcome of a submission in flight and everything the payer has typed.
 * Held anywhere else, the form empties whenever it leaves the composition.
 *
 * **Nothing typed reaches saved instance state**, so a form reopened after process death is an empty form. What
 * recovers a payment interrupted there is `PayInSubmissionState.Failed.retryKey`.
 *
 * **One form per [flow].** The typed values are the flow's, so two forms given the same one draw the same
 * boxes, and two given the same one with different configurations refill each other on every frame. A second
 * form on a screen takes a second flow.
 *
 * @param flow where a submission runs, and whose state this renders.
 * @param operation what the tap does: store the method, capture, or authorize.
 * @param configuration what to collect and how to arrange it.
 * @param labels wording decided at runtime; anything left out or blank comes from string resources.
 * @param style null takes `LocalPayInFormStyle`, then the host's theme.
 * @param initialValues what the boxes start with, for a payer whose details the caller already holds. Held
 *   exactly as typed input is, and replacing it starts the form again from the new values.
 * @param onCompleted the service accepted it.
 * @param onFailed it did not go through, with the typed cause and the fields the service rejected.
 * @param onMethodChanged the payer switched instrument. The values themselves stay in the form: a card
 *   number and a security code have no reason to cross into a host that no longer submits them.
 */
@Composable
public fun PayabliPayInForm(
    flow: PayabliPayInPaymentFlow,
    operation: PayabliPayInOperation,
    configuration: PayInFormConfiguration,
    modifier: Modifier = Modifier,
    labels: PayInFormLabels = PayInFormLabels(),
    style: PayInFormStyle? = null,
    initialValues: PayInFormValues? = null,
    onCompleted: (PayInSubmissionState.Succeeded) -> Unit,
    onFailed: (PayInSubmissionState.Failed) -> Unit,
    onMethodChanged: (PayInMethodType) -> Unit,
) {
    val submission by flow.state.collectAsState()

    // An authorization takes entered card data only, so a bank tab beside it is a form no request can be
    // made from.
    //
    // Keyed on what the narrowing reads rather than on the operation: a host building its operation inline hands
    // over a new instance on every recomposition, and this then copies the configuration on each one.
    val narrowingKey = operation.narrowingKey
    val offered = remember(narrowingKey, configuration) { operation.offering(configuration) }

    PayInFormContent(
        submission = submission,
        draft = flow.draft,
        configuration = offered,
        modifier = modifier,
        labels = labels,
        style = style,
        initialValues = initialValues,
        onSubmit = { values -> flow.start(operation, values) },
        onCompleted = { outcome ->
            try {
                onCompleted(outcome)
            } finally {
                flow.consume()
            }
        },
        onFailed = { outcome ->
            try {
                onFailed(outcome)
            } finally {
                flow.consume()
            }
        },
        onMethodChanged = onMethodChanged,
    )
}
