package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.model.PayabliErrorCode
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
import com.payabli.sdk.payin.client.PayInRoutes
import com.payabli.sdk.payin.client.TokenStorageClient
import com.payabli.sdk.payin.client.trimOrNull
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInRequest
import com.payabli.sdk.payin.model.PayInStoreRequest
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
     * The key of an attempt whose outcome nobody knows, against the payment it was for.
     *
     * A resend carries the same key, so the service recognizes the repeat instead of taking the money a
     * second time. Only a call naming the transaction it acts on has an entry: two form submissions of
     * equal value are two payments.
     *
     * Read and written under [inFlight].
     */
    private val unresolved = mutableMapOf<String, HeldKey>()

    /**
     * Whether the holder has already said it is full, so it says so once rather than once per payment.
     * Cleared by the next key that is kept, which needs room again.
     */
    private var reportedFull: Boolean = false

    /**
     * A key held for a payment, and when it was reserved. Not a `data class`: the synthesized `toString`
     * would put the key into any assertion failure or crash report that renders one.
     */
    private class HeldKey(
        val key: String,
        val reservedAt: Long,
        /**
         * Whether the service has been seen to hold this key. A repeat it refused is proof the attempt
         * arrived, so the entry never ages out.
         */
        val arrived: Boolean = false,
    )

    private val sink = MutableStateFlow<PayInSubmissionState>(PayInSubmissionState.Idle)

    val state: StateFlow<PayInSubmissionState> = sink.asStateFlow()

    /**
     * Runs [operation] against what the payer entered, at [entryPoint].
     *
     * Returns the terminal state, or null when a submission was already in flight — in which case nothing was
     * sent and the state is left as it stands.
     */
    suspend fun submit(
        entryPoint: String,
        operation: PayabliPayInOperation,
        values: PayInFormValues,
        onReserved: (Boolean) -> Unit = {},
    ): PayInSubmissionState? =
        // No payment named: two submissions of equal value are two payments, not a resend of one.
        perform(
            operation.event,
            entryPoint,
            onReserved,
            movesMoney = operation.movesMoney,
            payment = null,
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
     */
    suspend fun capture(
        entryPoint: String,
        request: PayInRequest,
    ): PayInSubmissionState? =
        // No payment named, for the reason `submit` gives: two captures of equal value are two payments, and
        // nothing here tells one from a resend of the other. A caller that knows better sets its own key.
        perform(TelemetryEvents.PAYIN_CAPTURE_COMPLETED, entryPoint, publishes = false, payment = null) { retry ->
            val key = retry.reserve(request.options.idempotencyKey)
            PayInSubmissionState.Succeeded.Payment(
                moneyIn.capture(entryPoint, request, PayInEnteredDetails.NONE, key),
            )
        }

    /** Places a hold from a request a caller built, on the same terms as [capture]. Reads no form. */
    suspend fun authorize(
        entryPoint: String,
        request: PayInRequest,
    ): PayInSubmissionState? =
        perform(TelemetryEvents.PAYIN_AUTHORIZE_COMPLETED, entryPoint, publishes = false, payment = null) { retry ->
            val key = retry.reserve(request.options.idempotencyKey)
            PayInSubmissionState.Succeeded.Payment(
                moneyIn.authorize(entryPoint, request, PayInEnteredDetails.NONE, key),
            )
        }

    /**
     * Stores the instrument in a request a caller built, on the same terms as [capture] for the buffers.
     * Reads no form, and sends no idempotency key: the store route has none to read.
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

    /** Captures a transaction authorized earlier, in full or in part. Reads no form. */
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

    /** Reverses a transaction. Reads no form, and takes only what identifies the transaction. */
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
     * Back to [PayInSubmissionState.Idle], for a screen taking a second payment.
     *
     * Refused while a submission is in flight, so a caller cannot clear the state out from under one.
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
     * The state machine, which every operation runs through.
     *
     * On [dispatcher], because building the body encodes JSON and walks buffers before the transport is reached,
     * and the caller's scope on a payment screen is the main thread.
     *
     * [publishes] is false for an operation nothing is drawing. [state] belongs to the form: it holds a terminal
     * outcome until the form has delivered it, and an operation the form did not start has no one to deliver to,
     * so publishing there would strand an outcome that only `consume` clears. Such a caller has the return value
     * instead. The single flight is still shared, so a void cannot run beside a submission in either order.
     */
    private suspend fun perform(
        event: String,
        entryPoint: String? = null,
        onReserved: (Boolean) -> Unit = {},
        publishes: Boolean = true,
        movesMoney: Boolean = true,
        payment: String?,
        call: suspend (RetryKey) -> PayInSubmissionState,
    ): PayInSubmissionState? {
        // Answered before the first suspension, so a caller starting this undispatched learns whether the single
        // flight was taken rather than inferring it from a state that is published before the guard is released.
        if (!inFlight.tryLock()) {
            onReserved(false)
            logger.debug(LogField.safe("event", "payin_submission_already_in_flight")) {
                "a submission is already in flight, so this one was refused"
            }
            report(event, TelemetryProperties.Outcome.REFUSED_LOCALLY, null, null, entryPoint)
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
            report(event, TelemetryProperties.Outcome.REFUSED_LOCALLY, null, null, entryPoint)
            return null
        }
        onReserved(true)
        val startedAt = elapsedRealtimeNanos()
        val retry = RetryKey(payment)
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
            outcome =
                PayInSubmissionState.Failed(
                    cause = if (unknown) PayInException.Unsettled(interrupted) else interrupted,
                    retryKey = retry.key,
                )
            throw cancellation
        } catch (failure: Exception) {
            outcome = failure.asFailed(retry, movesMoney)
        } finally {
            // Nothing here suspends, so all of it runs on the canceled path as it does on any other. That is
            // what makes an abandoned payment countable: it is the one outcome nobody is left to report.
            val finished = outcome
            if (finished != null) {
                val settled = if (payment != null) settle(payment, finished, retry) else finished
                outcome = settled
                if (publishes) sink.value = settled
                report(event, outcomeOf(settled), codeOf(settled), startedAt, entryPoint)
            }
            inFlight.unlock()
        }
        return outcome
    }

    /**
     * Keeps [payment]'s key where the outcome is unknown, forgets it where the service answered, and
     * answers with the outcome to report.
     *
     * An answer of any kind ends the attempt, so carrying this key into the next request would claim a
     * repeat that it is not.
     *
     * A minted key there was no room to keep is reported as none, not being sent again. A key the caller
     * chose is reported whatever the map did, the caller's own next request carrying it.
     */
    private fun settle(
        payment: String,
        outcome: PayInSubmissionState,
        retry: RetryKey,
    ): PayInSubmissionState {
        val failure = (outcome as? PayInSubmissionState.Failed)?.cause
        val key = retry.key
        when {
            key == null -> Unit
            failure == null || failure.answersThePayment(retry.reused) -> forget(payment, key)
            failure.code.leavesOutcomeUnknown -> hold(payment, HeldKey(key, retry.reservedAt))
            retry.reused && failure.code == PayabliErrorCode.CONFLICT ->
                hold(payment, HeldKey(key, retry.reservedAt, arrived = true))

            else -> Unit
        }
        return when {
            outcome !is PayInSubmissionState.Failed -> outcome
            outcome.retryKey == null || !retry.minted -> outcome
            unresolved[payment]?.key == outcome.retryKey -> outcome
            else -> PayInSubmissionState.Failed(outcome.cause, outcome.fieldErrors, retryKey = null)
        }
    }

    /**
     * Keeps [held] for [payment], without displacing a record that is protecting something else.
     *
     * A different key is not written: the held one is the only copy of an attempt nobody has an answer
     * for, and the next keyless call would send the wrong one. The same key merges, so the earliest
     * reservation stands and a conflict already seen is not forgotten.
     */
    private fun hold(
        payment: String,
        held: HeldKey,
    ) {
        val existing = unresolved[payment]
        when {
            existing == null && unresolved.size >= HELD_KEYS_MAX ->
                if (!reportedFull) {
                    reportedFull = true
                    logger.warn(LogField.safe("event", "payin_retry_keys_full")) {
                        "a payment's key was not held, because too many are unresolved at once"
                    }
                }

            existing == null -> {
                reportedFull = false
                unresolved[payment] = held
            }
            existing.key != held.key -> Unit
            else ->
                unresolved[payment] =
                    HeldKey(
                        held.key,
                        minOf(existing.reservedAt, held.reservedAt),
                        arrived = existing.arrived || held.arrived,
                    )
        }
    }

    /**
     * Forgets [payment]'s key when it is still [key].
     *
     * A caller answering under its own key while a held key is in flight would otherwise drop the attempt
     * nobody has an answer for.
     */
    private fun forget(
        payment: String,
        key: String,
    ) {
        if (unresolved[payment]?.key == key) unresolved.remove(payment)
    }

    /**
     * Whether this failure answers the payment rather than the attempt that carried it.
     *
     * A decline and a refusal the service made about the request are answers. A rejected credential, a
     * refusal to act at all and anything that never left the device leave the earlier attempt exactly as
     * unresolved. A conflict answers only a key the caller named; on one this SDK resent, the repeat is
     * what was refused.
     */
    private fun PayabliException.answersThePayment(reused: Boolean): Boolean =
        when (code) {
            PayabliErrorCode.PAYMENT_DECLINED -> true
            PayabliErrorCode.VALIDATION_ERROR -> this is PayabliValidationException
            PayabliErrorCode.CONFLICT -> !reused
            else -> false
        }

    /**
     * The key held for [payment], or null once it is too old to send.
     *
     * A key the service no longer recognizes is executed as a new payment, so sending one reads as
     * protection and takes the money twice. The clock starts at reservation, which is before the request
     * leaves the device, so what can be relied on is shorter than what the service honours.
     *
     * An entry the service was seen to hold is exempt, dropping it being the only one of the two that can
     * charge twice.
     */
    private fun stillWorthSending(
        payment: String,
        now: Long,
    ): HeldKey? {
        // Every entry, not just this payment's: a flow meets many transactions and revisits few.
        unresolved.values.removeAll {
            !it.arrived && now - it.reservedAt >= IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds
        }
        return unresolved[payment]
    }

    /**
     * The failure, with the field it blamed.
     *
     * Anything that is not a [PayabliException] is a defect in this SDK, and arrives as
     * [PayabliErrorCode.UNKNOWN] carrying its type and its frames but not its message: a message from inside a
     * body writer or a serializer can quote what it was given.
     *
     * **Two facts, and each has its own place.** Whether the payment's outcome is unknown is carried by the
     * cause, wrapped as [PayInException.Unsettled]; whether there is a key worth sending again is carried by
     * `retryKey`. They agree everywhere but one case, and that case is the reason they are separate: a
     * conflict this holder did not resend leaves the outcome unknown and names no key, the service having
     * just refused the only value there would be to send.
     *
     * A resent key is named for anything short of an answer, this holder keeping it. Any other is named
     * only where the request may have been carried out, since the host would have to carry it itself.
     */
    private fun Exception.asFailed(
        retry: RetryKey,
        movesMoney: Boolean,
    ): PayInSubmissionState.Failed {
        val cause =
            this as? PayabliException
                ?: PayabliGenericException(
                    PayabliErrorCode.UNKNOWN,
                    REASON_UNEXPECTED,
                    cause = RedactedCause(this),
                )
        val unknown = movesMoney && cause.leavesTheOutcomeUnknown(retry.reused)
        return PayInSubmissionState.Failed(
            cause = if (unknown) PayInException.Unsettled(cause) else cause,
            fieldErrors = PayInRejectedFields.of(this),
            retryKey =
                retry.key.takeIf {
                    unknown && (retry.reused || cause.code != PayabliErrorCode.CONFLICT)
                },
        )
    }

    /**
     * Whether the payment's outcome is unknown once this failure has arrived.
     *
     * A key this holder resent names an attempt nobody has an answer for, so anything short of an answer
     * about the payment leaves it exactly as unresolved. Otherwise the code decides, and it decides for one
     * more case than [leavesOutcomeUnknown] carries: a conflict establishes that a request under that key
     * got past the service's check, and never whether the payment was taken.
     *
     * **A conflict is not added to [leavesOutcomeUnknown] itself**, which decides which attempt is kept
     * alive rather than what a caller is told. Adding it there would report a key that was just refused,
     * and card-present reads the same property for a question this answer is not about.
     */
    private fun PayabliException.leavesTheOutcomeUnknown(reused: Boolean): Boolean =
        if (reused) {
            !answersThePayment(true)
        } else {
            code.leavesOutcomeUnknown || code == PayabliErrorCode.CONFLICT
        }

    /**
     * Reports how one submission ended.
     *
     * The boundary is form to transport, which is the span a payment incident asks about and the one no
     * per-request record can answer: a log line names one outcome, and the questions here are rates.
     *
     * Carries no instrument, no payer and no amount. [code] is the classification the failure already
     * published to the caller, which is a fixed set.
     */
    private fun report(
        event: String,
        outcome: String,
        code: String?,
        startedAt: Long?,
        entryPoint: String?,
    ) {
        // The entry point the request was sent to, which a capability can be pointed at independently of the
        // one the session was configured with. Reporting the session's would file it under another merchant.
        val attributed = if (entryPoint == null) session else session?.forEntryPoint(entryPoint)

        if (attributed != null) {
            TelemetryRecorders.recordFor(attributed, event) { measurements(outcome, code, startedAt) }
        } else {
            TelemetryRecorders.record(event) { measurements(outcome, code, startedAt) }
        }
    }

    private fun measurements(
        outcome: String,
        code: String?,
        startedAt: Long?,
    ): Map<String, String> =
        buildMap {
            put(TelemetryProperty.OUTCOME.key, outcome)
            code?.let { put(TelemetryProperty.CODE.key, it) }
            startedAt?.let {
                put(
                    TelemetryProperty.DURATION_MS.key,
                    TimeUnit.NANOSECONDS.toMillis(elapsedRealtimeNanos() - it).toString(),
                )
            }
        }

    /**
     * The six things that can happen to a payment, told apart.
     *
     * Keyed on the code the caller was told, so a record cannot disagree with the screen the payer saw. One
     * exception, and it is the type check below: a rejected field arrives as `VALIDATION_ERROR` whether this
     * module refused it or the service answered 400 with it, and those are the two halves of the one number
     * this value exists to give.
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
            cause.code == PayabliErrorCode.PAYMENT_DECLINED -> TelemetryProperties.Outcome.DECLINED
            cause.code == PayabliErrorCode.USER_CANCELLED -> TelemetryProperties.Outcome.INTERRUPTED
            cause.code == PayabliErrorCode.VALIDATION_ERROR ||
                cause.code == PayabliErrorCode.INVALID_CONFIGURATION
            -> TelemetryProperties.Outcome.REFUSED_LOCALLY
            else -> TelemetryProperties.Outcome.FAILED
        }

    private fun codeOf(state: PayInSubmissionState): String? =
        (state as? PayInSubmissionState.Failed)?.cause?.code?.wireName

    /**
     * The idempotency key of the request that went out, once there is one.
     *
     * Read by every failure that leaves the outcome unknown, which a cancellation is one of. The caller's own
     * request carries the key, so it is known only once that has been built.
     */
    private inner class RetryKey(
        private val payment: String?,
    ) {
        var key: String? = null
            private set

        /**
         * When the key this attempt sends was reserved, read as the key is chosen rather than as the call
         * began: encoding and dispatch sit between the two and would spend part of the window.
         *
         * A reused key keeps the reservation it already had, so a resend does not start the window again.
         */
        var reservedAt: Long = 0
            private set

        /** Whether the key sent was minted here rather than chosen by the caller or already held. */
        var minted: Boolean = false
            private set

        /** Whether the key sent is the one held for this payment rather than the caller's or a new one. */
        var reused: Boolean = false
            private set

        /**
         * The key this attempt sends.
         *
         * [supplied] first, a caller that set a key naming the attempt itself. Then the key held for this
         * payment, so a resend is the same request. Otherwise a new one.
         *
         * **The held key's age is read here, where the key is sent, and not where the call began.** What
         * sits between the two is a dispatch and whatever the host does with the thread, and this clock
         * counts device sleep, so a check taken earlier can be arbitrarily stale by the time the request
         * leaves. That is a separate moment from the reservation being stamped when a key is chosen: this
         * decides whether a held key may still be sent, that decides what its window is measured from.
         */
        fun reserve(supplied: String?): String {
            val now = elapsedRealtimeNanos()
            val held = payment?.let { stillWorthSending(it, now) }
            val chosen =
                when {
                    held != null && supplied == null -> {
                        reservedAt = held.reservedAt
                        reused = true
                        held.key
                    }

                    supplied == null -> {
                        reservedAt = now
                        minted = true
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
