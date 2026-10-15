package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliAttempt
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.model.PayabliValidationException
import com.payabli.sdk.core.model.leavesOutcomeUnknown
import com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.core.telemetry.TelemetrySessionContext
import com.payabli.sdk.payin.client.MoneyInClient
import com.payabli.sdk.payin.client.PayInEnteredDetails
import com.payabli.sdk.payin.client.PayInKeyRefusal
import com.payabli.sdk.payin.client.PayInRoutes
import com.payabli.sdk.payin.client.TokenStorageClient
import com.payabli.sdk.payin.client.trimOrNull
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInRequest
import com.payabli.sdk.payin.model.PayInStoreRequest
import com.payabli.sdk.payin.model.PayInTransaction
import com.payabli.sdk.payin.model.RedactedCause
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * One payment form's submission: what state it is in, and the one call that changes it.
 *
 * **The caller owns the scope.** Every entry point suspends, so whoever calls decides whether a capture dies
 * with the screen. Canceling does not un-charge a card.
 *
 * **One holder per form, and no singleton.** A holder keeps its terminal state — a result, or an exception
 * carrying wording from the wire — for as long as it lives, so its lifetime is a screen's. A host holds
 * it wherever its own screen state lives.
 *
 * [state] is a `StateFlow`, which replays its latest value, so a collector arriving after a configuration
 * change sees `Submitting` or the outcome immediately.
 */
internal class PayInSubmission(
    private val moneyIn: MoneyInClient,
    private val storage: TokenStorageClient,
    private val dispatcher: CoroutineDispatcher,
    private val newIdempotencyKey: () -> String,
    private val elapsedRealtimeNanos: () -> Long,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.NETWORK),
    private val session: TelemetrySessionContext? = null,
) {
    /**
     * The single flight, held for the whole call.
     *
     * [Mutex.tryLock] is one step. A boolean read and then written is two, and two callers can both pass the
     * read.
     */
    private val inFlight = Mutex()

    /**
     * Keys of attempts whose outcome nobody knows, newest first. A call naming a transaction holds one per
     * transaction; capture and authorize hold one per route, the newest open attempt's, and only a declared
     * retry is sent it. Read and written under [inFlight].
     */
    private val unresolved = mutableListOf<HeldKey>()

    /**
     * Whether the holder has already said it is full, so it says so once rather than once per payment.
     * Cleared by the next key that is kept, which needs room again.
     */
    private var reportedFull: Boolean = false

    /**
     * A key held for a payment, and when it was reserved. Not a `data class`: the synthesized `toString`
     * would put the key into any assertion failure or crash report that renders one.
     *
     * @property payment what names the payment: its route, and the transaction where the call names one.
     */
    private class HeldKey(
        val payment: String,
        val key: String,
        val reservedAt: Long,
    )

    private val sink = MutableStateFlow<PayInSubmissionState>(PayInSubmissionState.Idle)

    val state: StateFlow<PayInSubmissionState> = sink.asStateFlow()

    /**
     * Runs [operation] against what the payer entered, at [entryPoint].
     *
     * Returns the terminal state, or null when a submission was already in flight — in which case nothing was
     * sent and the state is left as it stands.
     *
     * @param entryPoint the partner integration point the submission is attributed to.
     * @param operation what the submission does: store the method, capture, or authorize.
     * @param values what the payer entered, and the instrument they entered it for.
     * @param onReserved told whether this submission took the single flight.
     * @return the terminal state, or null when a submission was already in flight.
     */
    suspend fun submit(
        entryPoint: String,
        operation: PayabliPayInOperation,
        values: PayInFormValues,
        onReserved: (Boolean) -> Unit = {},
    ): PayInSubmissionState? =
        perform(
            operation.event,
            entryPoint,
            onReserved,
            movesMoney = operation.movesMoney,
            payment = operation.holding,
            claimsHeldKey = operation.declaresRetry,
        ) { retry ->
            // The customer and the description the payer typed, which are not part of the instrument. Read
            // once here, so all three operations carry what the same form collected.
            val entered = PayInEnteredDetails.of(values)
            when (operation) {
                is PayabliPayInOperation.StoreMethod ->
                    PayInFormInstrument.useInstrument(values) { instrument ->
                        PayInSubmissionState.Succeeded.Method(
                            storage.storeMethod(entryPoint, instrument, operation.options, entered),
                        )
                    }

                is PayabliPayInOperation.Capture ->
                    PayInFormInstrument.usePaymentMethod(values) { method ->
                        val key = retry.reserve(operation.options.idempotencyKey)
                        PayInSubmissionState.Succeeded.Payment(
                            moneyIn.capture(entryPoint, PayInRequest(method, operation.options), entered, key),
                        )
                    }

                is PayabliPayInOperation.Authorize ->
                    PayInFormInstrument.usePaymentMethod(values) { method ->
                        val key = retry.reserve(operation.options.idempotencyKey)
                        PayInSubmissionState.Succeeded.Payment(
                            moneyIn.authorize(entryPoint, PayInRequest(method, operation.options), entered, key),
                        )
                    }
            }
        }

    /**
     * Takes a payment from a request a caller built, rather than from a form. Reads no form.
     *
     * The buffers inside [request] belong to whoever built them and are not closed here, which is the
     * opposite of the form's path: there the instrument is built per submission and closed with it.
     *
     * @param entryPoint the partner integration point the payment is attributed to.
     * @param request the payment to take, its buffers the caller's.
     * @return the terminal state, or null when a submission was already in flight.
     */
    suspend fun capture(
        entryPoint: String,
        request: PayInRequest,
    ): PayInSubmissionState? =
        perform(
            TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
            entryPoint,
            publishes = false,
            payment = PayInRoutes.CAPTURE,
            claimsHeldKey = request.options.attempt == PayabliAttempt.Retry,
        ) { retry ->
            val key = retry.reserve(request.options.idempotencyKey)
            PayInSubmissionState.Succeeded.Payment(
                moneyIn.capture(entryPoint, request, PayInEnteredDetails.NONE, key),
            )
        }

    /**
     * Places a hold from a request a caller built, on the same terms as [capture]. Reads no form.
     *
     * @param entryPoint the partner integration point the payment is attributed to.
     * @param request the payment to place a hold on, its buffers the caller's.
     * @return the terminal state, or null when a submission was already in flight.
     */
    suspend fun authorize(
        entryPoint: String,
        request: PayInRequest,
    ): PayInSubmissionState? =
        perform(
            TelemetryEvents.PAYIN_AUTHORIZE_COMPLETED,
            entryPoint,
            publishes = false,
            payment = PayInRoutes.AUTHORIZE,
            claimsHeldKey = request.options.attempt == PayabliAttempt.Retry,
        ) { retry ->
            val key = retry.reserve(request.options.idempotencyKey)
            PayInSubmissionState.Succeeded.Payment(
                moneyIn.authorize(entryPoint, request, PayInEnteredDetails.NONE, key),
            )
        }

    /**
     * Stores the instrument in a request a caller built, on the same terms as [capture] for the buffers.
     * Reads no form, and sends no idempotency key: the store route has none to read.
     *
     * @param entryPoint the partner integration point the method is stored against.
     * @param request the method to store, its buffers the caller's.
     * @return the terminal state, or null when a submission was already in flight.
     */
    suspend fun storeMethod(
        entryPoint: String,
        request: PayInStoreRequest,
    ): PayInSubmissionState? =
        perform(
            TelemetryEvents.PAYIN_STORE_METHOD_COMPLETED,
            entryPoint,
            publishes = false,
            movesMoney = false,
            payment = null,
        ) {
            PayInSubmissionState.Succeeded.Method(storage.storeMethod(entryPoint, request.instrument, request.options))
        }

    /**
     * Captures a transaction authorized earlier, in full or in part. Reads no form.
     *
     * @param entryPoint the partner integration point the payment is attributed to.
     * @param request the authorization to capture, carrying the attempt's key or one to mint.
     * @return the terminal state, or null when a submission was already in flight.
     */
    suspend fun captureAuthorized(
        entryPoint: String,
        request: PayInAuthorizedRequest,
    ): PayInSubmissionState? =
        perform(
            TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
            entryPoint,
            publishes = false,
            payment = "${PayInRoutes.CAPTURE_AUTHORIZED}:${request.transId.trim()}",
        ) { retry ->
            val key = retry.reserve(request.idempotencyKey)
            PayInSubmissionState.Succeeded.Payment(moneyIn.captureAuthorized(request, key))
        }

    /**
     * Reverses a transaction. Reads no form, and takes only what identifies the transaction.
     *
     * @param entryPoint the partner integration point the reversal is attributed to.
     * @param transId the transaction to reverse.
     * @param idempotencyKey the attempt's key, or null for the held one to be resent, or one minted.
     * @return the terminal state, or null when a submission was already in flight.
     */
    suspend fun void(
        entryPoint: String,
        transId: String,
        idempotencyKey: String?,
    ): PayInSubmissionState? =
        perform(
            TelemetryEvents.PAYIN_VOID_COMPLETED,
            entryPoint,
            publishes = false,
            payment = "${PayInRoutes.VOID}:${transId.trim()}",
        ) { retry ->
            val key = retry.reserve(idempotencyKey)
            PayInSubmissionState.Succeeded.Payment(moneyIn.void(transId, key))
        }

    /**
     * Reads a transaction back. It moves no money and holds no key, so it runs beside a submission in flight,
     * never changes [state], and a failure is never [PayInException.Unsettled].
     *
     * @param entryPoint the partner integration point the read is attributed to.
     * @param transId the transaction to read.
     * @return the transaction, or the failure the read ended in.
     */
    suspend fun readTransaction(
        entryPoint: String,
        transId: String,
    ): Result<PayInTransaction> {
        val event = TelemetryEvents.PAYIN_READ_COMPLETED
        val startedAt = elapsedRealtimeNanos()
        return try {
            val transaction = withContext(dispatcher) { moneyIn.readTransaction(transId) }
            report(event, TelemetryProperties.Outcome.APPROVED, null, startedAt, entryPoint, fromForm = false)
            Result.success(transaction)
        } catch (cancellation: CancellationException) {
            report(event, TelemetryProperties.Outcome.INTERRUPTED, null, startedAt, entryPoint, fromForm = false)
            throw cancellation
        } catch (failure: Exception) {
            val cause =
                failure as? PayabliException
                    ?: PayabliGenericException(
                        PayabliErrorType.UNKNOWN,
                        REASON_UNEXPECTED,
                        cause = RedactedCause(failure),
                    )
            report(event, outcomeOf(cause), cause.type, startedAt, entryPoint, fromForm = false)
            Result.failure(cause)
        }
    }

    /**
     * Back to [PayInSubmissionState.Idle], for a screen taking a second payment.
     *
     * Refused while a submission is in flight, so a caller cannot clear the state out from under one.
     *
     * @return true when the state is [PayInSubmissionState.Idle] again; false when a submission is in flight.
     */
    fun reset(): Boolean {
        val current = sink.value
        if (current == PayInSubmissionState.Idle) return true
        // The state, not the submission's lock: that lock is still held while the outcome is published, and a
        // collector acknowledging what it just saw runs inside that window.
        if (current == PayInSubmissionState.Submitting) return false
        return sink.compareAndSet(current, PayInSubmissionState.Idle)
    }

    /**
     * The state machine, which every operation runs through, on [dispatcher] because the body is
     * built here and the caller's scope on a payment screen is the main thread. [publishes] is
     * false for an operation nothing is drawing, which has the return value instead.
     *
     * @param event the telemetry event naming the operation.
     * @param entryPoint the partner integration point, for the report.
     * @param onReserved told whether this submission took the single flight.
     * @param publishes whether the outcome is published to [state] as the form's.
     * @param movesMoney whether the operation can take a payment, which decides an interruption's class.
     * @param payment what names the payment a retry key is held against, or null for none.
     * @param claimsHeldKey whether a key held for [payment] is sent rather than a new one being minted.
     * @param call the operation itself, handed its retry key.
     * @return the terminal state, or null when a submission was already in flight.
     */
    private suspend fun perform(
        event: String,
        entryPoint: String? = null,
        onReserved: (Boolean) -> Unit = {},
        publishes: Boolean = true,
        movesMoney: Boolean = true,
        payment: String?,
        claimsHeldKey: Boolean = true,
        call: suspend (RetryKey) -> PayInSubmissionState,
    ): PayInSubmissionState? {
        // Answered before the first suspension, so a caller starting this undispatched learns whether the single
        // flight was taken rather than inferring it from a state that is published before the guard is released.
        if (!inFlight.tryLock()) {
            onReserved(false)
            logger.debug(LogField.safe("event", "payin_submission_already_in_flight")) {
                "a submission is already in flight, so this one was refused"
            }
            report(event, TelemetryProperties.Outcome.REFUSED_LOCALLY, null, null, entryPoint, publishes)
            return null
        }
        // Starting here would overwrite an outcome nothing has read yet, and a taken payment would leave no
        // record of itself. `reset` is what clears the way. Only for a caller that publishes: one that does
        // not cannot overwrite anything, and gating it on the form's state is what would make two headless
        // calls in a row impossible.
        if (publishes && sink.value != PayInSubmissionState.Idle) {
            inFlight.unlock()
            onReserved(false)
            logger.debug(LogField.safe("event", "payin_submission_outcome_unacknowledged")) {
                "an outcome has not been acknowledged, so this submission was refused"
            }
            report(event, TelemetryProperties.Outcome.REFUSED_LOCALLY, null, null, entryPoint, publishes)
            return null
        }
        onReserved(true)
        val startedAt = elapsedRealtimeNanos()
        val retry = RetryKey(payment, claimsHeldKey)
        if (publishes) sink.value = PayInSubmissionState.Submitting
        var outcome: PayInSubmissionState? = null
        try {
            outcome = withContext(dispatcher) { call(retry) }
        } catch (cancellation: CancellationException) {
            // Rethrown: a coroutine that swallows its own cancellation stops being cancellable. The state still
            // records it, because the charge may have landed and the retry key is what a second attempt needs.
            //
            // The key is what says how far this got. Reserved, and the request may have been carried out, so
            // it reports the open outcome every other interrupted attempt does. Not reserved, and nothing was
            // sent. Gated on movesMoney as every other classification here is, so an operation that reserves
            // a key without moving money would not start reporting an open payment.
            val interrupted = PayInException.Interrupted()
            val unknown = movesMoney && retry.key != null
            outcome = PayInSubmissionState.Failed(if (unknown) PayInException.Unsettled(interrupted) else interrupted)
            throw cancellation
        } catch (failure: Exception) {
            outcome = failure.asFailed(retry, movesMoney)
        } finally {
            // Nothing here suspends, so all of it runs on the canceled path as it does on any other. That is
            // what makes an abandoned payment countable: it is the one outcome nobody is left to report.
            val finished = outcome
            if (finished != null) {
                if (payment != null) settle(payment, finished, retry)
                if (publishes) sink.value = finished
                report(event, outcomeOf(finished), codeOf(finished), startedAt, entryPoint, publishes)
            }
            inFlight.unlock()
        }
        return outcome
    }

    /**
     * Keeps [payment]'s key where the outcome is unknown, and forgets it where the service answered. An
     * answer of any kind ends the attempt, so carrying this key into the next request would claim a repeat
     * that it is not.
     *
     * @param payment what names the payment.
     * @param outcome the state the call ended in.
     * @param retry the key this attempt carried.
     */
    private fun settle(
        payment: String,
        outcome: PayInSubmissionState,
        retry: RetryKey,
    ) {
        val failure = (outcome as? PayInSubmissionState.Failed)?.cause
        val key = retry.key
        when {
            key == null -> Unit
            failure == null || failure.answersThePayment() -> forget(payment, key)
            // Ahead of the general case: a refused resend keeps the key without restarting its clock.
            retry.reused && failure.type == PayabliErrorType.RETRY_REFUSED ->
                hold(HeldKey(payment, key, retry.reservedAt))

            failure.type.leavesOutcomeUnknown -> hold(HeldKey(payment, key, retry.sentAt))
            else -> Unit
        }
    }

    /**
     * Keeps [held] without displacing what protects another payment: the same key merges, keeping the latest
     * reservation, a transaction gets no second key, and a capture or authorization replaces its route's key.
     *
     * @param held the payment, its key and the reservation to keep.
     */
    private fun hold(held: HeldKey) {
        sweep(elapsedRealtimeNanos())
        val same = unresolved.indexOfFirst { it.payment == held.payment && it.key == held.key }
        // A retry claims the newest open attempt, so an older key on the route can never be the one to send.
        if (same < 0 && !held.payment.namesATransaction) unresolved.removeAll { it.payment == held.payment }
        when {
            same >= 0 -> {
                val existing = unresolved[same]
                unresolved[same] =
                    HeldKey(held.payment, held.key, maxOf(existing.reservedAt, held.reservedAt))
            }

            held.payment.namesATransaction && unresolved.any { it.payment == held.payment } -> Unit
            unresolved.size >= HELD_KEYS_MAX -> {
                if (!reportedFull) {
                    reportedFull = true
                    logger.warn(LogField.safe("event", "payin_retry_keys_full")) {
                        "a payment's key was not held, because too many are unresolved at once"
                    }
                }
            }

            else -> {
                reportedFull = false
                unresolved.add(0, held)
            }
        }
    }

    /**
     * Whether this payment names a transaction, as capturing an authorization and voiding do. A capture and
     * an authorization are named by their route alone.
     */
    private val String.namesATransaction: Boolean
        get() = this != PayInRoutes.CAPTURE && this != PayInRoutes.AUTHORIZE

    /**
     * Forgets [payment]'s key [key], and no other.
     *
     * A caller answering under its own key while a held key is in flight would otherwise drop the attempt
     * nobody has an answer for.
     *
     * @param payment what names the payment.
     * @param key the key an answer arrived under.
     */
    private fun forget(
        payment: String,
        key: String,
    ) {
        unresolved.removeAll { it.payment == payment && it.key == key }
    }

    /**
     * Whether this failure answers the payment rather than the attempt that carried it: a decline, a conflict
     * and a refusal of the request are; a rejected credential, a refusal to act at all, a refused held key
     * ([PayabliErrorType.RETRY_REFUSED]) and anything that never left the device are not.
     *
     * @return true when the failure answers the payment; false otherwise.
     */
    private fun PayabliException.answersThePayment(): Boolean =
        when (type) {
            PayabliErrorType.PAYMENT_DECLINED -> true
            PayabliErrorType.VALIDATION_ERROR -> this is PayabliValidationException
            PayabliErrorType.CONFLICT -> true
            else -> false
        }

    /**
     * The key held for [payment], or null when none is worth sending: past the window the service runs a key
     * as a new payment.
     *
     * @param payment what names the payment.
     * @param now the clock, read as the key is chosen.
     * @return the key held for [payment], or null once it is too old to send.
     */
    private fun stillWorthSending(
        payment: String,
        now: Long,
    ): HeldKey? {
        sweep(now)
        return unresolved.firstOrNull { it.payment == payment }
    }

    /**
     * Drops every key too old to send. Every entry, not just one payment's: a flow meets many transactions
     * and revisits few, and an entry left in place takes room a later payment needs.
     *
     * @param now the clock.
     */
    private fun sweep(now: Long) {
        unresolved.removeAll { now - it.reservedAt >= IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds }
    }

    /**
     * The failure, with the field it blamed. A non-[PayabliException] arrives as [PayabliErrorType.UNKNOWN],
     * keeping its type and frames but not its message; the service refusing a key this holder resent is
     * [PayabliErrorType.RETRY_REFUSED], which proves only that the earlier attempt arrived.
     *
     * @param retry the key this attempt carried.
     * @param movesMoney whether the operation can take a payment.
     * @return the failed state, with the field it blamed.
     */
    private fun Exception.asFailed(
        retry: RetryKey,
        movesMoney: Boolean,
    ): PayInSubmissionState.Failed {
        val cause =
            when {
                this !is PayabliException ->
                    PayabliGenericException(PayabliErrorType.UNKNOWN, REASON_UNEXPECTED, cause = RedactedCause(this))

                this is PayInKeyRefusal ->
                    if (retry.reused) {
                        PayabliGenericException(PayabliErrorType.RETRY_REFUSED, REASON_RETRY_REFUSED)
                    } else {
                        PayabliGenericException(PayabliErrorType.CONFLICT, reason)
                    }

                else -> this
            }
        val unknown = movesMoney && cause.leavesTheOutcomeUnknown(retry.reused)
        return PayInSubmissionState.Failed(
            cause = if (unknown) PayInException.Unsettled(cause) else cause,
            fieldErrors = PayInRejectedFields.of(this),
        )
    }

    /**
     * Whether the payment's outcome is unknown once this failure has arrived. A key this holder resent
     * names an attempt nobody has an answer for, so anything short of an answer leaves it unresolved.
     * A conflict answers the key and leaves the payment open, so it is decided here, not on the property.
     *
     * @param reused whether the key sent was the one held for this payment.
     * @return true when the failure leaves the payment's outcome unknown; false otherwise.
     */
    private fun PayabliException.leavesTheOutcomeUnknown(reused: Boolean): Boolean =
        when {
            type == PayabliErrorType.CONFLICT -> true
            reused -> !answersThePayment()
            else -> type.leavesOutcomeUnknown
        }

    /**
     * Reports how one submission ended, over the span a payment incident asks about and no per-request
     * record can answer. Carries no instrument, no payer and no amount. [code] is the classification
     * the failure already published to the caller, which is a fixed set.
     *
     * @param event the telemetry event naming the operation.
     * @param outcome how the submission ended.
     * @param code the failure's classification, or null when it succeeded.
     * @param startedAt when the submission began, or null when it never started.
     * @param entryPoint the integration point the request was sent to, or null for the session's.
     * @param fromForm whether a form started the submission.
     */
    private fun report(
        event: String,
        outcome: String,
        code: PayabliErrorType?,
        startedAt: Long?,
        entryPoint: String?,
        fromForm: Boolean,
    ) {
        // The entry point the request was sent to, which a capability can be pointed at independently of the
        // one the session was configured with. Reporting the session's would file it under another merchant.
        val attributed = if (entryPoint == null) session else session?.forEntryPoint(entryPoint)

        if (attributed != null) {
            TelemetryRecorders.recordFor(attributed, event) { measurements(outcome, code, startedAt, fromForm) }
        } else {
            TelemetryRecorders.record(event) { measurements(outcome, code, startedAt, fromForm) }
        }
    }

    private fun measurements(
        outcome: String,
        code: PayabliErrorType?,
        startedAt: Long?,
        fromForm: Boolean,
    ): Map<String, String> =
        buildMap {
            put(TelemetryProperty.OUTCOME.key, outcome)
            put(
                TelemetryProperty.ORIGIN.key,
                if (fromForm) TelemetryProperties.Origin.FORM else TelemetryProperties.Origin.DIRECT,
            )
            code?.let {
                put(TelemetryProperty.CODE.key, it.wireName)
                put(TelemetryProperty.ERROR_NUMBER.key, it.code.toString())
            }
            startedAt?.let {
                put(
                    TelemetryProperty.DURATION_MS.key,
                    TimeUnit.NANOSECONDS.toMillis(elapsedRealtimeNanos() - it).toString(),
                )
            }
        }

    /**
     * The six things that can happen to a payment, told apart. Keyed on the code the caller was told,
     * so a record cannot disagree with the screen the payer saw. One exception, and it is the type
     * check below: a rejected field arrives as `VALIDATION_ERROR` whichever side refused it.
     *
     * @param state the state the submission ended in.
     * @return the outcome, in the report's vocabulary.
     */
    private fun outcomeOf(state: PayInSubmissionState): String =
        when (state) {
            is PayInSubmissionState.Succeeded -> TelemetryProperties.Outcome.APPROVED
            is PayInSubmissionState.Failed -> outcomeOf(state.cause)
            else -> TelemetryProperties.Outcome.FAILED
        }

    private fun outcomeOf(cause: PayabliException): String =
        when {
            // A request was spent and the service answered it, so this is the service refusing rather than
            // this module declining to ask.
            cause is PayabliValidationException -> TelemetryProperties.Outcome.REFUSED
            cause.type == PayabliErrorType.PAYMENT_DECLINED -> TelemetryProperties.Outcome.DECLINED
            cause.type == PayabliErrorType.USER_CANCELLED -> TelemetryProperties.Outcome.INTERRUPTED
            cause.type == PayabliErrorType.VALIDATION_ERROR ||
                cause.type == PayabliErrorType.INVALID_CONFIGURATION
            -> TelemetryProperties.Outcome.REFUSED_LOCALLY
            else -> TelemetryProperties.Outcome.FAILED
        }

    private fun codeOf(state: PayInSubmissionState): PayabliErrorType? =
        (state as? PayInSubmissionState.Failed)?.cause?.type

    /**
     * The idempotency key of the request that went out, once there is one.
     *
     * Read by every failure that leaves the outcome unknown, which a cancellation is one of. The caller's own
     * request carries the key, so it is known only once that has been built.
     */
    private inner class RetryKey(
        private val payment: String?,
        private val claimsHeldKey: Boolean,
    ) {
        var key: String? = null
            private set

        /**
         * When the key this attempt sends was reserved, read as the key is chosen rather than as the call
         * began: encoding and dispatch sit between the two and would spend part of the window.
         *
         * A reused key keeps the reservation it already had; [sentAt] is this send's, which restarts the clock
         * when the send may have reached the service and was not refused as a repeat.
         */
        var reservedAt: Long = 0
            private set

        /** When this send reserved its key, whether or not the key was already held. */
        var sentAt: Long = 0
            private set

        /** Whether the key sent is the one held for this payment rather than the caller's or a new one. */
        var reused: Boolean = false
            private set

        /**
         * The key this attempt sends: [supplied] first, then the key held for this payment where the
         * attempt may claim one, otherwise a new one. The held key's age is read here, where the key is
         * sent, not where the call began:
         * this clock counts device sleep, so a check taken earlier can be arbitrarily stale by the
         * time the request leaves.
         *
         * @param supplied the caller's own key, or null for one to be chosen.
         * @return the key this attempt sends.
         */
        fun reserve(supplied: String?): String {
            val now = elapsedRealtimeNanos()
            sentAt = now
            val held = payment?.takeIf { claimsHeldKey }?.let { stillWorthSending(it, now) }
            val chosen =
                when {
                    held != null && supplied == null -> {
                        reservedAt = held.reservedAt
                        reused = true
                        held.key
                    }

                    supplied == null -> {
                        reservedAt = now
                        newIdempotencyKey()
                    }

                    else -> {
                        reservedAt = now
                        // Held as the header carries it, so the key that answers matches the one kept.
                        // Blank stays blank: the header check is what refuses it.
                        supplied.trimOrNull() ?: supplied
                    }
                }
            key = chosen
            return chosen
        }
    }

    internal companion object {
        const val REASON_UNEXPECTED = "The payment could not be submitted"
        const val REASON_RETRY_REFUSED = "Retry refused (409)"

        /**
         * How many payments may hold a key at once.
         *
         * Past it a key is minted and not held, which is what every payment had before this holder: one
         * key per attempt and no resend. Nothing held is evicted, each entry being the only thing that
         * would recognise its own repeat.
         */
        const val HELD_KEYS_MAX: Int = 16
    }
}
