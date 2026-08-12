package com.payabli.sdk.payin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormStyle
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.payment.PayabliPayInPaymentFlow
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
 * **A refusal marks the field and keeps the values.** The form owns the screen at the moment a service refuses
 * a field, so the payer can correct it in place; [onFailed] carries the same failure for a host that logs it or
 * navigates away from it.
 *
 * **Consumes no window insets.** Give it a scrolling viewport that accounts for the keyboard.
 *
 * **Retention is [flow]'s, not this composable's.** Hold the flow in something that survives a configuration
 * change and the outcome of a submission in flight is still delivered afterwards. This function keeps only
 * what the payer typed and which fields a refusal named.
 *
 * @param flow where a submission runs, and whose state this renders.
 * @param operation what the tap does: store the method, capture, or authorize.
 * @param configuration what to collect and how to arrange it.
 * @param labels wording decided at runtime; anything left out or blank comes from string resources.
 * @param style null takes `LocalPayInFormStyle`, then the host's theme.
 * @param initialValues what the boxes start with, for a payer whose details the caller already holds. Held
 *   exactly as typed input is, and replacing it starts the form again from the new values.
 * @param onCompleted the service accepted it. Call [PayabliPayInPaymentFlow.acknowledge] once handled.
 * @param onFailed it did not go through, with the typed cause and the fields the refusal named.
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
    onCompleted: (PayInSubmissionState.Succeeded) -> Unit = {},
    onFailed: (PayInSubmissionState.Failed) -> Unit = {},
    onMethodChanged: (PayInMethodType) -> Unit = {},
) {
    val submission by flow.state.collectAsState()

    PayInFormContent(
        submission = submission,
        configuration = configuration,
        modifier = modifier,
        labels = labels,
        style = style,
        initialValues = initialValues,
        onSubmit = { values -> flow.start(operation, values) },
        onCompleted = onCompleted,
        onFailed = onFailed,
        onMethodChanged = onMethodChanged,
    )
}
