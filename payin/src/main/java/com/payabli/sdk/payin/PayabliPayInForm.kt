package com.payabli.sdk.payin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.payin.form.PayInFormConfiguration
import com.payabli.sdk.payin.form.PayInFormDraft
import com.payabli.sdk.payin.form.PayInFormLabels
import com.payabli.sdk.payin.form.PayInFormStyle
import com.payabli.sdk.payin.form.PayInFormSubmitter
import com.payabli.sdk.payin.form.PayInMethodType
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import com.payabli.sdk.payin.payment.narrowingKey
import com.payabli.sdk.payin.payment.offering
import com.payabli.sdk.payin.payment.paymentDetails
import com.payabli.sdk.payin.payment.step
import com.payabli.sdk.payin.telemetry.PayInFormReports
import com.payabli.sdk.payin.ui.PayInFormContent
import com.payabli.sdk.payin.ui.PayInSubmitText
import com.payabli.sdk.payin.ui.deliver
import com.payabli.sdk.payin.ui.rememberPayInFormLease
import com.payabli.sdk.payin.ui.rememberPayInFormShownDraft
import kotlinx.coroutines.flow.first

/**
 * The Payabli payment form.
 *
 * Collects a card or a bank account and submits it. The payer's tap runs [operation] through [payIn], and one
 * of [onCompleted] or [onFailed] fires with the outcome.
 *
 * **[onMethodChanged] fires when the method on screen changes, whoever moved it.** A host reprices on it,
 * so a configuration that moves the method reaches it exactly as a tap does, and the form opens silently.
 * The values stay in the form: a card number and a security code have no reason to cross into a host that
 * no longer submits them.
 *
 * **It looks like the app it is in.** With no [style] it takes its colors, type and shapes from the host's
 * `MaterialTheme`, so light, dark and dynamic color arrive with nothing passed. Use
 * `PayInFormStyleOverrides` to change one value, or `LocalPayInFormStyle` for every form in a tree.
 *
 * **The instrument goes once the service may have acted on it.** The card number, expiry, security code, routing
 * and account number are emptied after an approval or a refusal by the service, and kept after a refusal before
 * that, such as an amount the request cannot send. A field the service rejects is marked; the payer's details stay.
 *
 * **Nothing in a failure is loggable.** [onFailed] carries the failure so a host can act on its category or
 * navigate away from it. `PayabliException.reason` and `detail` are neither shown nor logged: some of them can
 * quote what was submitted, so a screen shows a message for the category and a record takes the code.
 *
 * **Consumes no window insets.** Give it a scrolling viewport that accounts for the keyboard.
 *
 * **Retention is [payIn]'s, not this composable's.** Hold it in something that survives a configuration
 * change, and a rotation keeps both the outcome of a submission in flight and everything the payer has typed.
 * Held anywhere else, the form empties whenever it leaves the composition.
 *
 * **Nothing typed reaches saved instance state**, so a form reopened after process death is an empty form, and
 * the interrupted payment's key died with the process: find that payment before charging again. Within one
 * process, a retry is a submission with `attempt` set to `PayabliAttempt.Retry` and the payment's `paymentReference`.
 *
 * **One form per [payIn].** A second form given one already drawn throws `IllegalStateException` in a debuggable
 * app; otherwise it draws empty and disabled, with no callbacks, until the first leaves. A second form on a screen
 * takes a second one. Forms drawn in a list each take a `key`, and forms in separate `ComposeView`s
 * each take a view id. Handing a form another [payIn] empties what was typed for the one before, so the previous one
 * handed back opens empty. A submission still in flight when that happens reaches [onCompleted] or [onFailed] once
 * it lands while the form is drawn, and marks nothing on the form now drawn.
 *
 * @param payIn where a submission runs, and whose state this renders.
 * @param operation what the tap does: store the method, capture, or authorize.
 * @param configuration what to collect and how to arrange it.
 * @param modifier the layout modifier.
 * @param labels wording decided at runtime; anything left out or blank comes from string resources.
 * @param style null takes `LocalPayInFormStyle`, then the host's theme.
 * @param onCompleted the service accepted it.
 * @param onFailed it did not complete, with the typed cause; where the outcome is unknown the cause is `Unsettled` and the operation may have been carried out.
 * @param onMethodChanged the displayed method moved, by a payer switch or a configuration change; the values do not cross into the callback.
 */
@Composable
public fun PayabliPayInForm(
    payIn: PayabliPayIn,
    operation: PayabliPayInOperation,
    configuration: PayInFormConfiguration,
    modifier: Modifier = Modifier,
    labels: PayInFormLabels = PayInFormLabels(),
    style: PayInFormStyle? = null,
    onCompleted: (PayInFormSuccess) -> Unit,
    onFailed: (PayabliException) -> Unit,
    onMethodChanged: (PayInMethodType) -> Unit,
) {
    // The typed values, the reports and the submission the tap starts are this module's, not the contract's:
    // driving a form is not something a host does to a `PayabliPayIn`, so none of it is on the interface.
    // A `when` rather than a cast, so this is the compiler's problem rather than a payer's. `PayabliPayIn` is
    // sealed and has one implementation today; a second added beside it would make this branch non-exhaustive
    // and fail the build, where a cast would compile and throw the first time the form was drawn.
    val impl =
        when (payIn) {
            is PayInPaymentFlow -> payIn
        }
    if (rememberPayInFormLease(impl.lease)) {
        LeasedPayInForm(impl, operation, configuration, modifier, labels, style, onCompleted, onFailed, onMethodChanged)
    } else {
        // Another form draws this flow: this one waits with a draft of its own, so nothing typed is drawn twice.
        PayInFormContent(
            submission = PayInSubmissionState.Idle,
            draft = remember { PayInFormDraft() },
            sent = { false },
            configuration = operation.offering(configuration),
            reports = PayInFormReports.None,
            submitText = PayInSubmitText.of(operation),
            modifier = modifier,
            labels = labels,
            style = style,
            inert = true,
        )
    }
}

/**
 * The form drawing [impl], which holds its lease.
 *
 * @param impl the flow the form draws.
 * @param operation what the tap does.
 * @param configuration what to collect and how to arrange it.
 * @param modifier the layout modifier.
 * @param labels the caller's wording.
 * @param style the form's style, or the theme's.
 * @param onCompleted the service accepted it.
 * @param onFailed it did not complete.
 * @param onMethodChanged the displayed method moved.
 */
@Composable
private fun LeasedPayInForm(
    impl: PayInPaymentFlow,
    operation: PayabliPayInOperation,
    configuration: PayInFormConfiguration,
    modifier: Modifier,
    labels: PayInFormLabels,
    style: PayInFormStyle?,
    onCompleted: (PayInFormSuccess) -> Unit,
    onFailed: (PayabliException) -> Unit,
    onMethodChanged: (PayInMethodType) -> Unit,
) {
    val shown = rememberPayInFormShownDraft(impl.draft)
    // After the composition is applied, so a discarded one empties nothing.
    SideEffect { shown.show(impl.draft, impl.lease) }
    val submission by impl.submissionState.collectAsState()

    // An authorization takes entered card data only, so a bank tab beside it is a form no request can be
    // made from.
    //
    // Keyed on what the narrowing reads rather than on the operation: a host building its operation inline hands
    // over a new instance on every recomposition, and this then copies the configuration on each one.
    val narrowingKey = operation.narrowingKey
    val offered = remember(narrowingKey, configuration) { operation.offering(configuration) }

    // One form per PayabliPayIn, and the step is a fixed word per operation, so together they change exactly
    // when the form on screen does. Not the operation itself: written inline it is a new instance on every
    // recomposition, which would count one opened form once per keystroke.
    val step = operation.step
    LaunchedEffect(impl, step) { impl.reports.presented(step) }

    // A flow submitted and then replaced before its outcome landed still reports it to the host, drawing nothing.
    val completed by rememberUpdatedState(onCompleted)
    val failed by rememberUpdatedState(onFailed)
    shown.awaited.filter { it !== impl }.forEach { submitted ->
        key(submitted) {
            LaunchedEffect(submitted) {
                submitted.deliverWhenItLands(shown, { completed(it) }, { failed(it) })
                shown.awaited.remove(submitted)
            }
        }
    }

    PayInFormContent(
        submission = submission,
        draft = impl.draft,
        sent = impl::sent,
        configuration = offered,
        reports = impl.reports,
        submitText = PayInSubmitText.of(operation),
        amounts = operation.paymentDetails,
        modifier = modifier,
        labels = labels,
        style = style,
        onSubmit = { values ->
            // Before the send, so a submit that never reaches the service is still counted.
            impl.reports.submitted(operation.step)
            impl.start(operation, values, offered.accountHolderType).also { started ->
                if (started && impl !in shown.awaited) shown.awaited += impl
            }
        },
        onCompleted = { outcome ->
            try {
                onCompleted(outcome)
            } finally {
                shown.awaited.remove(impl)
                impl.consume()
            }
        },
        onFailed = { outcome ->
            try {
                onFailed(outcome)
            } finally {
                shown.awaited.remove(impl)
                impl.consume()
            }
        },
        onMethodChanged = onMethodChanged,
        formMethod = shown.method,
        submitter = shown,
    )
}

/**
 * Waits for the outcome of a submission to this flow made by a form that now draws another, and hands it to the
 * caller, marking no field and showing no message. Nothing is handed over when a form drawing this flow already did,
 * or when the submission pending is another form's.
 *
 * @param form the form that submitted.
 * @param onCompleted the service accepted it.
 * @param onFailed it did not complete.
 */
private suspend fun PayInPaymentFlow.deliverWhenItLands(
    form: PayInFormSubmitter,
    onCompleted: (PayInFormSuccess) -> Unit,
    onFailed: (PayabliException) -> Unit,
) {
    if (draft.submitter !== form) return
    val outcome = submissionState.first { it != PayInSubmissionState.Submitting }
    if (!draft.submissionPending || draft.submitter !== form) return
    reports.refusedFields((outcome as? PayInSubmissionState.Failed)?.fieldErrors.orEmpty())
    try {
        draft.submissionPending =
            outcome.deliver({ if (sent(outcome)) draft.clearInstrument() }, onCompleted, onFailed)
        if (!draft.submissionPending) draft.submitter = null
    } finally {
        consume()
    }
}
