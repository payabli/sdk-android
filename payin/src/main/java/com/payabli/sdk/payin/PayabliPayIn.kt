package com.payabli.sdk.payin

import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInRequest
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.model.PayInTransactionOptions
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
 * **A form is one way to build a request, never the only way to reach an operation.** A host that has already
 * collected the instrument, or that draws its own checkout, calls the member and reads what it returns.
 * Drawing [PayabliPayInForm] is the other way in, and it reaches the same operations.
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
     * **A call made directly does not appear here.** Nothing is drawing it, and a terminal state on this flow
     * stands until the form has delivered it, so an outcome published by a call the form did not start would
     * wait for a reader that never comes. Such a call answers with its return value instead.
     */
    public abstract val state: StateFlow<PayInSubmissionState>

    /**
     * Takes the payment [request] describes, returning what the service said.
     *
     * `Result` rather than a thrown exception, because a decline is an outcome a caller acts on rather than a
     * defect. The failure is a `PayabliException` and only some of them are a `PayInException`, on the same
     * terms as [captureAuthorizedTransaction].
     *
     * **The buffers inside the request are the caller's to close.** A `SensitiveDigits` this SDK did not build
     * is not overwritten here, so close what you built once the call has returned — `use` is the ordinary way.
     * The form's own path builds the instrument per submission and closes it, which is why a form caller has
     * nothing to do.
     *
     * **This call moves money, so it always carries an idempotency key**, minted for the attempt when
     * [PayInTransactionOptions.idempotencyKey] is unset.
     *
     * **A minted one is not reused, so set your own to retry safely.** A read timeout, a cancellation or a
     * response that could not be decoded all leave it unknown whether the payment was taken, and only a repeat
     * carrying the same key is the same attempt. Nothing here can tell a retry of this payment from a second
     * payment of equal value — unlike [captureAuthorizedTransaction], which has a transaction to name — so a
     * key this SDK minted is not held for a later call, and calling again without your own is a second payment
     * rather than a retry.
     */
    public abstract suspend fun capture(request: PayInRequest): Result<PayInResult>

    /**
     * Places a hold without taking it, which [captureAuthorizedTransaction] later completes and
     * [voidTransaction] releases.
     *
     * Takes a card or a cloud device. An account, a check, cash and a stored method are refused before
     * anything is sent, because a round trip to learn that is worse than an answer now.
     *
     * A stored card is the one of those that can be held, and this SDK cannot ask for it yet: it names a
     * stored method as a kind of its own rather than naming the method it stands for. Charge one with
     * [capture] until the type can say it.
     *
     * Everything [capture] says about the failure, the buffers and the idempotency key holds here too.
     */
    public abstract suspend fun authorize(request: PayInRequest): Result<PayInResult>

    /**
     * Captures a transaction authorized earlier, in full or in part.
     *
     * `Result` rather than a thrown exception, because a decline is an outcome a caller acts on rather than a
     * defect.
     *
     * **The failure is a `PayabliException`, and only some of them are a `PayInException`.** A refusal the
     * service described arrives as `PayInException.Refused` or `.ServiceError`; a rejected field, a rejected
     * credential and a rate limit arrive as the `:core` types this SDK raises everywhere else. A failure
     * that leaves the outcome open is [PayInException.Unsettled] instead of the type it wraps, so branch on
     * [PayabliException.code], which is the underlying classification either way and covers both.
     *
     * **This call moves money, so it always carries an idempotency key.** Set
     * [PayInAuthorizedRequest.idempotencyKey] to choose it; left unset, one is minted for the attempt. A
     * failure that leaves it unknown whether the capture was applied arrives as [PayInException.Unsettled],
     * and what repeating the call is worth then depends on who chose the key.
     *
     * **A key you set is the one sent, every time.** So repeating the call with it is the same attempt
     * rather than a second one, from any instance and after a restart.
     *
     * **A repeat the service refuses outright is the end of what repeating can tell you.** It arrives as
     * [PayInException.Unsettled] over [PayabliErrorCode.CONFLICT], which says a request under that key got
     * past the service's check and nothing about whether the capture was applied. Repeating again is
     * refused the same way, and a fresh key applies a second capture. Read the transaction back instead.
     *
     * **A key the SDK minted is resent only while it still holds one** for this transaction, and how long
     * that is is not published. It holds none past the point where too many payments are unresolved at
     * once, none in a second instance and none after a restart. Where it holds none, repeating the call is
     * a new capture under a new key, so set and persist a key of your own where that matters.
     *
     * **Whether a repeat is recognised at all is the service's.** Persisting a key lets you send it again;
     * it does not make the service remember it. Past the point where it has stopped, the same key is
     * carried out as a new request.
     */
    public abstract suspend fun captureAuthorizedTransaction(request: PayInAuthorizedRequest): Result<PayInResult>

    /**
     * Reverses a transaction, releasing an authorization's hold or undoing a capture that has not settled.
     *
     * Which transactions can still be reversed is the service's to decide, and is not mirrored here: a state
     * it will not reverse comes back as the refusal it sent, carrying its own reason.
     *
     * [transId] is the transaction to reverse, as [PayInTransaction.paymentTransId] reported it. This route
     * holds and resends an idempotency key exactly as [captureAuthorizedTransaction] does, so the paragraph
     * there is the contract for both.
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
