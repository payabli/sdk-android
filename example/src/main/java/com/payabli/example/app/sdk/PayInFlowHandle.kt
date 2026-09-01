package com.payabli.example.app.sdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.payabli.sdk.payin.PayabliPayIn
import com.payabli.sdk.payin.payment.PayInSubmissionState

/**
 * A screen's grip on the pay-in it submits through.
 *
 * The `PayabliPayIn` itself stays in here. A screen needs two answers about it and the form needs it, so this
 * hands out the answers and keeps the type: outside this package nothing names one, which is what makes the
 * package the whole of the integration rather than most of it.
 */
class PayInFlowHandle internal constructor(
    internal val payIn: PayabliPayIn,
) {
    /**
     * Whether it is holding anything: a submission in flight, or an outcome nobody has taken yet.
     *
     * Both payment screens read it around a token recheck, which builds a session and replaces the one they
     * hold. Replaced while it holds a request, the request still reaches the service and can move money;
     * replaced while it holds a terminal state the form has not consumed, the outcome reaches neither
     * callback. Either way the form observes a new idle one and the screen offers Submit again.
     */
    val isBusy: Boolean get() = payIn.state.value != PayInSubmissionState.Idle

    /** Whether a submission is in flight, recomposing the caller when that changes. */
    @Composable
    fun isSubmitting(): Boolean {
        val submission by payIn.state.collectAsState()
        return submission is PayInSubmissionState.Submitting
    }

    /**
     * Reverses [transId].
     *
     * Not visible in [isSubmitting] or [isBusy]: the SDK publishes this to no state, because nothing is
     * drawing it. A screen that offers it tracks its own in-flight flag.
     */
    suspend fun voidTransaction(transId: String): PayInOutcome = payIn.voidTransaction(transId).toOutcome()
}

/** Reads as not busy when there is none yet, which is how a screen reads it before the first token check. */
internal fun PayInFlowHandle?.isBusy(): Boolean = this != null && isBusy
