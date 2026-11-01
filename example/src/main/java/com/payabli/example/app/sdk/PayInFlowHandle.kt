package com.payabli.example.app.sdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.payabli.sdk.payin.PayabliPayIn
import kotlinx.coroutines.CoroutineScope

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
     * A token recheck replaces the session behind the flow, and an outcome standing undelivered reaches
     * neither callback once replaced; the flight is the SDK's flag, the outcome half this app's own.
     */
    val isBusy: Boolean

    /**
     * Marks the outcome delivered, closing the window [isBusy] keeps a recheck out of.
     *
     * [PaymentFormHost] calls it from where the form hands this app the outcome, which is the only place
     * one stands: a direct call's outcome returns to its caller instead.
     */
    fun outcomeTaken()

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
     * Reverses [transId] under the key the SDK holds for it, so a repeated reversal is recognised. Not in
     * [isSubmitting] or [isBusy]: the SDK publishes a void to no state, so a screen tracks its own flag.
     */
    suspend fun voidTransaction(transId: String): PayInOutcome

    /**
     * Reads [transId] back.
     *
     * @param transId the transaction to read.
     * @return what the read found, or why it failed.
     */
    suspend fun readTransaction(transId: String): PayInReadOutcome
}

/**
 * The one a session produces, which is every handle outside a test.
 *
 * [scope] is the caller's, and the outcome window's watcher runs on it, beside the submissions.
 */
internal class SdkPayInFlowHandle(
    private val payIn: PayabliPayIn,
    scope: CoroutineScope,
) : PayInFlowHandle {
    private val outcomes = PayInOutcomeLatch(payIn.isSubmitting, scope)

    override val isBusy: Boolean get() = payIn.isSubmitting.value || outcomes.holds

    override fun outcomeTaken() = outcomes.taken()

    override val formTarget: PayabliPayIn get() = payIn

    @Composable
    override fun isSubmitting(): Boolean {
        val submitting by payIn.isSubmitting.collectAsState()
        return submitting
    }

    override suspend fun voidTransaction(transId: String): PayInOutcome = payIn.voidTransaction(transId).toOutcome()

    override suspend fun readTransaction(transId: String): PayInReadOutcome =
        payIn.readTransaction(transId).toReadOutcome()
}

/** Reads as not busy when there is none yet, which is how a screen reads it before the first token check. */
internal fun PayInFlowHandle?.isBusy(): Boolean = this != null && isBusy
