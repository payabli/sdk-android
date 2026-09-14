package com.payabli.example.app.sdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.payabli.sdk.payin.PayabliPayIn
import com.payabli.sdk.payin.payment.PayInSubmissionState

/**
 * A screen's grip on the pay-in it submits through.
 *
 * The `PayabliPayIn` stays behind this. A screen needs two answers about it and the form needs the object
 * itself, so this hands out the answers and keeps the type: outside this package nothing names one, which is
 * what makes the package the whole of the integration rather than most of it.
 *
 * **An interface, because `PayabliPayIn` is sealed.** Only the SDK implements that type, so a JVM test can
 * neither build one nor stand in for one, and a view model that reverses a payment is reachable from a
 * device alone unless the seam is here.
 */
interface PayInFlowHandle {
    /**
     * Whether it is holding anything: a submission in flight, or an outcome nobody has taken yet.
     *
     * Both payment screens read it around a token recheck, which builds a session and replaces the one they
     * hold. Replaced while it holds a request, the request still reaches the service and can move money;
     * replaced while it holds a terminal state the form has not consumed, the outcome reaches neither
     * callback. Either way the form observes a new idle one and the screen offers Submit again.
     */
    val isBusy: Boolean

    /**
     * What [PaymentFormHost] draws, or null for a handle that is not backed by the SDK.
     *
     * Only a test double is unbacked, and no test draws a form over one, so the form asks for this and says
     * so rather than carrying a nullable through the screens.
     */
    val formTarget: PayabliPayIn?

    /** Whether a submission is in flight, recomposing the caller when that changes. */
    @Composable
    fun isSubmitting(): Boolean

    /**
     * Reverses [transId], under [idempotencyKey].
     *
     * Not visible in [isSubmitting] or [isBusy]: the SDK publishes this to no state, because nothing is
     * drawing it. A screen that offers it tracks its own in-flight flag.
     *
     * The SDK mints one when this is left out, so the key is required here as the app's own choice rather
     * than to fill a gap: this screen names the attempt so its own controls can reason about it, where a
     * host with nothing to say about the attempt would leave it out and let the SDK carry it.
     */
    suspend fun voidTransaction(
        transId: String,
        idempotencyKey: String,
    ): PayInOutcome
}

/** The one a session produces, which is every handle outside a test. */
internal class SdkPayInFlowHandle(
    private val payIn: PayabliPayIn,
) : PayInFlowHandle {
    override val isBusy: Boolean get() = payIn.state.value != PayInSubmissionState.Idle

    override val formTarget: PayabliPayIn get() = payIn

    @Composable
    override fun isSubmitting(): Boolean {
        val submission by payIn.state.collectAsState()
        return submission is PayInSubmissionState.Submitting
    }

    override suspend fun voidTransaction(
        transId: String,
        idempotencyKey: String,
    ): PayInOutcome = payIn.voidTransaction(transId, idempotencyKey).toOutcome()
}

/** Reads as not busy when there is none yet, which is how a screen reads it before the first token check. */
internal fun PayInFlowHandle?.isBusy(): Boolean = this != null && isBusy
