package com.payabli.example.app.sdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInPaymentFlow

/**
 * A screen's grip on the flow it submits through.
 *
 * The flow itself stays in here. A screen needs two answers about it and the form needs the flow, so this
 * hands out the answers and keeps the type: outside this package nothing names one, which is what makes the
 * package the whole of the integration rather than most of it.
 */
class PayInFlowHandle internal constructor(
    internal val flow: PayabliPayInPaymentFlow,
) {
    /**
     * Whether the flow is holding anything: a submission in flight, or an outcome nobody has taken yet.
     *
     * Both payment screens read it around a token recheck, which builds a session and replaces the flow they
     * hold. Replaced while it holds a request, the request still reaches the service and can move money;
     * replaced while it holds a terminal state the form has not consumed, the outcome reaches neither
     * callback. Either way the form observes a new idle flow and the screen offers Submit again.
     */
    val isBusy: Boolean get() = flow.state.value != PayInSubmissionState.Idle

    /** Whether a submission is in flight, recomposing the caller when that changes. */
    @Composable
    fun isSubmitting(): Boolean {
        val submission by flow.state.collectAsState()
        return submission is PayInSubmissionState.Submitting
    }
}

/** Reads as not busy when there is no flow yet, which is how a screen reads it before the first token check. */
internal fun PayInFlowHandle?.isBusy(): Boolean = this != null && isBusy
