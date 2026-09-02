package com.payabli.sdk.payin

import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInResult
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
 *
 * **Sealed, so this SDK is the only thing that implements it.** [PayabliPayInForm] draws the implementation
 * built here and reaches members that are not on this contract, so an implementation from anywhere else
 * could not drive a form. Build one with [invoke].
 *
 * **A class rather than an interface, so the restriction does not rest on how a member happens to be
 * spelled.** A sealed interface is enforced by the Kotlin compiler and, in bytecode, by a
 * `PermittedSubclasses` attribute that exists only from class-file version 61; this module targets Java 11,
 * which is 55, so that attribute is absent. What still stopped a Java implementation was incidental: two
 * members return `Result`, a value class, so their JVM names carry a `-` suffix that Java cannot declare,
 * leaving any Java class abstract. A member returning a plain type would remove that by accident. A sealed
 * class has a private constructor instead, which holds whatever the members return.
 */
public sealed class PayabliPayIn {
    /**
     * Where the form's current submission has got to: what the form renders, and what a host reads for its
     * own chrome.
     *
     * **The two calls below do not appear here.** Nothing is drawing them, and a terminal state on this flow
     * stands until the form has delivered it, so an outcome published by a call the form did not start would
     * wait for a reader that never comes. Those calls answer with their return value instead.
     */
    public abstract val state: StateFlow<PayInSubmissionState>

    /**
     * Captures a transaction authorized earlier, in full or in part.
     *
     * `Result` rather than a thrown exception, because a decline is an outcome a caller acts on rather than a
     * defect. The failure is a `PayInException` carrying what the service said.
     *
     * **This call moves money, so set [PayInAuthorizedRequest.idempotencyKey] to retry it safely.** A read
     * timeout, a cancellation or a response that could not be decoded all leave it unknown whether the
     * capture was applied, and only a repeat carrying the same key is recognized as the same attempt. Nothing
     * is minted here: a key this SDK invented would not reach a caller holding a `Result`, so it could not be
     * resent, and the attempt would read as retryable while it is not.
     */
    public abstract suspend fun captureAuthorizedTransaction(request: PayInAuthorizedRequest): Result<PayInResult>

    /**
     * Reverses a transaction, releasing an authorization's hold or undoing a capture that has not settled.
     *
     * Which transactions can still be reversed is the service's to decide, and is not mirrored here: a state
     * it will not reverse comes back as the refusal it sent, carrying its own reason.
     *
     * @param transId the transaction to reverse, as [PayInTransaction.paymentTransId] reported it.
     * @param idempotencyKey makes a repeated send the same attempt rather than a second one. Supply it to
     *   retry safely after a failure that leaves the outcome unknown; absent, none is sent and a retry is a
     *   new attempt. Nothing is minted, for the reason given on [captureAuthorizedTransaction].
     */
    public abstract suspend fun voidTransaction(
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
        ): PayabliPayIn = PayInPaymentFlow.over(session, entryPoint, scope)
    }
}
