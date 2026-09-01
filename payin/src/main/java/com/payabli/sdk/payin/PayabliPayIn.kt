package com.payabli.sdk.payin

import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.payment.PayInPaymentFlow
import com.payabli.sdk.payin.payment.PayInSubmissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Card-not-present payments for one entry point: everything this module offers that is not a screen.
 *
 * The type a host holds, and the same instance [PayabliPayInForm] draws. One of these owns one submission
 * at a time, so a form submission and a call made here cannot overlap in either order.
 *
 * **Hold one per screen, in whatever survives that screen's configuration changes** — a `ViewModel`, a
 * Decompose component, a presenter. [state] replays its latest value, so a collector arriving after a
 * rotation sees `Submitting` or the outcome rather than nothing.
 *
 * Taking a payment, authorizing one and storing a method are reached by drawing [PayabliPayInForm], because
 * each of them needs an instrument the payer enters. The two members here need no instrument and no form,
 * which is why they are callable directly.
 */
public interface PayabliPayIn {
    /**
     * Where the form's current submission has got to: what the form renders, and what a host reads for its
     * own chrome.
     *
     * **The two calls below do not appear here.** Nothing is drawing them, and a terminal state on this flow
     * stands until the form has delivered it, so an outcome published by a call the form did not start would
     * wait for a reader that never comes. Those calls answer with their return value instead.
     */
    public val state: StateFlow<PayInSubmissionState>

    /**
     * Captures a transaction authorized earlier, in full or in part.
     *
     * `Result` rather than a thrown exception, because a decline is an outcome a caller acts on rather than a
     * defect. The failure is a `PayInException` carrying what the service said.
     */
    public suspend fun captureAuthorizedTransaction(request: PayInAuthorizedRequest): Result<PayInResult>

    /**
     * Reverses a transaction, releasing an authorization's hold or undoing a capture that has not settled.
     *
     * Which transactions can still be reversed is the service's to decide, and is not mirrored here: a state
     * it will not reverse comes back as the refusal it sent, carrying its own reason.
     *
     * @param transId the transaction to reverse, as [PayInTransaction.paymentTransId] reported it.
     * @param idempotencyKey makes a repeated send the same attempt rather than a second one. Minted per
     *   attempt when absent.
     */
    public suspend fun voidTransaction(
        transId: String,
        idempotencyKey: String? = null,
    ): Result<PayInResult>

    public companion object {
        /**
         * Builds one over [session], for [entryPoint].
         *
         * @param session an initialized session, whose transport carries the bearer, the one 401 recovery and
         *   the replay rule. Nothing here holds a credential or a token path of its own.
         * @param entryPoint the partner integration point every request is sent to.
         * @param scope where a submission started by the form runs. `viewModelScope` is the ordinary answer:
         *   it outlives a configuration change, so an outcome still arrives after a rotation, and it is
         *   cancelled when the screen goes for good. A scope tied to the composition —
         *   `rememberCoroutineScope` — cancels on rotation and loses the outcome of a request that has
         *   already reached the service.
         */
        public operator fun invoke(
            session: PayabliSession,
            entryPoint: String,
            scope: CoroutineScope,
        ): PayabliPayIn = PayInPaymentFlow(session, entryPoint, scope)
    }
}
