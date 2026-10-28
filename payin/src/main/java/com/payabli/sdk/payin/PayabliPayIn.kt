package com.payabli.sdk.payin

import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInRequest
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.model.PayInStoreRequest
import com.payabli.sdk.payin.model.PayInStoredMethod
import com.payabli.sdk.payin.model.PayInTransaction
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
     * **This call moves money, so it always carries an idempotency key, which the SDK mints and holds.** The
     * payment is named by [PayInTransactionOptions.paymentReference], or by one the SDK mints when that is unset.
     * A read timeout, a cancellation or an answer that could not be decoded leaves it unknown whether the
     * payment was taken; the failure is [PayInException.Unsettled], carrying that reference. Submitting the same
     * reference with `attempt` set to `PayabliAttempt.Retry` resends its key, so the service recognises the
     * repeat. A new payment naming a reference whose outcome is still unknown is refused as
     * [PayabliErrorType.PAYMENT_REFERENCE_OPEN] and not sent, and so is any call on a payment another instance is
     * still sending. The key is held in memory for every instance on the same entry point and environment, while
     * the service would still recognise it, and for at most 16 unresolved payments there at once; past that, or
     * after a restart, a retry goes out under a new key, so read the transaction back instead.
     *
     * **A declared retry describes the same payment.** One that describes a different payment is refused as
     * [PayabliErrorType.RETRY_NOT_SAME_PAYMENT] and not sent, and the earlier payment's key stays held.
     *
     * **A declared retry the service refuses arrives as [PayInException.Unsettled] over
     * [PayabliErrorType.RETRY_REFUSED].** It says the earlier attempt reached the service, and nothing about
     * whether the payment was taken. Check that payment before trying again.
     *
     * @param request the payment to take, its buffers the caller's to close.
     * @return the outcome, or the failure.
     */
    public abstract suspend fun capture(request: PayInRequest): Result<PayInResult>

    /**
     * Stores [request]'s instrument, so a later transaction charges it without the details again.
     *
     * The failure is a `PayabliException` and never [PayInException.Unsettled], because storing moves no money.
     * The buffers are the caller's to close, as on [capture]. No idempotency key is sent, so calling again after
     * a failure can store a second copy.
     *
     * @param request the method to store, its buffers the caller's to close.
     * @return the stored method, or the failure.
     */
    public abstract suspend fun storeMethod(request: PayInStoreRequest): Result<PayInStoredMethod>

    /**
     * Places a hold without taking it, which [captureAuthorizedTransaction] later completes and
     * [voidTransaction] releases.
     *
     * Takes a card, a stored card, and a cloud device. Refuses every other method before sending the
     * request.
     *
     * Everything [capture] says about the failure, the buffers and the idempotency key holds here too.
     *
     * @param request the payment to hold, its buffers the caller's to close.
     * @return the outcome, or the failure.
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
     * [PayabliException.type], which is the underlying classification either way and covers both.
     *
     * **This call moves money, so it always carries an idempotency key, which the SDK mints and holds for the
     * transaction.** A failure that leaves it unknown whether the capture was applied arrives as
     * [PayInException.Unsettled], and calling again with the same transaction resends the held key. A call on
     * a transaction another instance is still sending is refused as [PayabliErrorType.PAYMENT_REFERENCE_OPEN]
     * and not sent.
     *
     * **A repeat the service refuses outright is the end of what repeating can tell you.** It arrives as
     * [PayInException.Unsettled] over [PayabliErrorType.RETRY_REFUSED], which says a request under the held key
     * got past the service's check and nothing about whether the capture was applied. Repeating again is
     * refused the same way. Read the transaction back with [readTransaction] instead.
     *
     * **The key is resent only while the SDK still holds one** for this transaction. It holds none past the
     * point where too many payments are unresolved at once and none after a restart, and the service stops
     * recognising a key after a while; read the transaction back there.
     *
     * @param request the authorization to capture, in full or in part.
     * @return the outcome, or the failure.
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
     *
     * @param transId the transaction to reverse.
     * @return the outcome, or the failure.
     */
    public abstract suspend fun voidTransaction(transId: String): Result<PayInResult>

    /**
     * Reads a transaction back by its identifier, to learn what became of it.
     *
     * This is how an outcome left open by [PayInException.Unsettled] is resolved, when it names the payment: pass
     * its [PayInException.Unsettled.paymentTransId], and read [PayInTransaction.status]. A status whose outcome is
     * not known may still change, so read again later rather than repeating the payment. When
     * [PayInException.Unsettled.paymentTransId] is null, the answer named no transaction. After [voidTransaction] or
     * [captureAuthorizedTransaction], read back the identifier you passed. After [capture] or [authorize], find the
     * transaction in the Payabli portal by its `orderId`.
     *
     * A read moves no money and holds no key, so it runs beside a submission in flight, and a failed read is
     * never [PayInException.Unsettled]: repeating it is always safe.
     *
     * @param transId the transaction to read, as [PayInTransaction.paymentTransId] reported it.
     * @return the transaction, carrying [PayInTransaction.status]; or the failure, a `PayabliException` whose
     * [PayabliException.type] is [PayabliErrorType.PERMISSION_DENIED] when this credential may not read it, and
     * [PayabliErrorType.TRANSACTION_NOT_FOUND] when none is visible under [transId] yet. That is not proof no payment
     * was made: read again later rather than charging again.
     */
    public abstract suspend fun readTransaction(transId: String): Result<PayInTransaction>

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
         * @return the card-not-present capability, over the session.
         */
        public operator fun invoke(
            session: PayabliSession,
            entryPoint: String,
            scope: CoroutineScope,
        ): PayabliPayIn = PayInPaymentFlow.over(session, entryPoint, scope)
    }
}
