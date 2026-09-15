package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.model.PayabliValidationException
import com.payabli.sdk.core.model.leavesOutcomeUnknown
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.core.telemetry.TelemetrySessionContext
import com.payabli.sdk.payin.client.MoneyInClient
import com.payabli.sdk.payin.client.PayInEnteredDetails
import com.payabli.sdk.payin.client.PayInRoutes
import com.payabli.sdk.payin.client.TokenStorageClient
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInRequest
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
     * A resend of that payment carries the same key, so the service recognizes the repeat instead of taking
     * the money a second time. Only a call that names the transaction it acts on has an entry here: capturing
     * the same authorization twice is one payment by definition, where two form submissions of equal value
     * are two payments and telling them apart is not this holder's to guess.
     *
     * Read and written under [inFlight], which is held for the whole of [perform].
     */
    private val unresolved = mutableMapOf<String, HeldKey>()

    /**
     * A key held for a payment, and when it was reserved.
     *
     * Not a `data class`: the synthesized `toString` would put the key into any assertion failure or crash
     * report that renders one.
     */
    private class HeldKey(
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
     */
    suspend fun submit(
        entryPoint: String,
        operation: PayabliPayInOperation,
        values: PayInFormValues,
        onReserved: (Boolean) -> Unit = {},
    ): PayInSubmissionState? =
        // No payment named: two form submissions of equal value are two payments, and nothing here can tell
        // one from a resend of the other. Each mints its own key.
        perform(operation.event, entryPoint, onReserved, payment = null) { retry ->
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
        val retry = RetryKey(payment?.let { stillWorthSending(it, startedAt) }, startedAt)
        if (publishes) sink.value = PayInSubmissionState.Submitting
        var outcome: PayInSubmissionState? = null
        try {
            outcome = withContext(dispatcher) { call(retry) }
        } catch (cancellation: CancellationException) {
            // Rethrown: a coroutine that swallows its own cancellation stops being cancellable. The state still
            // records it, because the charge may have landed and the retry key is what a second attempt needs.
            outcome = PayInSubmissionState.Failed(PayInException.Interrupted(), retryKey = retry.key)
            throw cancellation
        } catch (failure: Exception) {
            outcome = failure.asFailed(retry)
        } finally {
            // Nothing here suspends, so all of it runs on the canceled path as it does on any other. That is
            // what makes an abandoned payment countable: it is the one outcome nobody is left to report.
            outcome?.let {
                if (publishes) sink.value = it
                if (payment != null) settle(payment, it, retry)
                report(event, outcomeOf(it), codeOf(it), startedAt, entryPoint)
            }
            inFlight.unlock()
        }
        return outcome
    }

    /**
     * Keeps [payment]'s key where the outcome is unknown, and forgets it where the service answered.
     *
     * An answer of any kind ends the attempt, so what the caller sends next is a different request and
     * carrying this key into it would claim a repeat that it is not.
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
            failure == null || failure.answersThePayment(retry.reused) -> unresolved.remove(payment)
            failure.code.leavesOutcomeUnknown -> unresolved[payment] = HeldKey(key, retry.reservedAt)
            else -> Unit
        }
    }

    /**
     * Whether this failure is an answer about the payment rather than about the attempt that carried it.
     *
     * A decline and a refusal the service made about the request are answers. A rejected credential, a
     * refusal to act at all and anything that never left the device are not: the earlier attempt is exactly
     * as unresolved afterwards, so its key is still the one to send.
     *
     * A conflict answers only a key the caller named. On one this SDK resent, what was refused is the
     * repeat.
     */
    private fun PayabliException.keepsTheKey(reused: Boolean): Boolean =
        code.leavesOutcomeUnknown || (code == PayabliErrorCode.CONFLICT && reused)

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
     * Dropped rather than sent, because a key that is no longer recognized is carried out as a new payment:
     * it would read as protection and take the money a second time. The clock starts when the key is
     * reserved, which is before the request leaves the device, so what can be relied on is shorter than what
     * the service honours by however long the attempt took. Short by a margin rather than exact, because the
     * two directions cost differently: stopping early mints a key where a repeat would have been refused,
     * which costs a refusal, and stopping late charges a payer twice.
     */
    private fun stillWorthSending(
        payment: String,
        now: Long,
    ): HeldKey? {
        // Every entry, not just this payment's: a flow that meets many transactions revisits few of them,
        // so keying the sweep on the lookup would hold every key it ever minted for as long as it lives.
        unresolved.values.removeAll { now - it.reservedAt >= HELD_KEY_WINDOW_NANOS }
        return unresolved[payment]
    }

    /**
     * The failure, with the field it blamed.
     *
     * Anything that is not a [PayabliException] is a defect in this SDK, and arrives as
     * [PayabliErrorCode.UNKNOWN] carrying its type and its frames but not its message: a message from inside a
     * body writer or a serializer can quote what it was given.
     */
    private fun Exception.asFailed(retry: RetryKey): PayInSubmissionState.Failed {
        val cause =
            this as? PayabliException
                ?: PayabliGenericException(
                    PayabliErrorCode.UNKNOWN,
                    REASON_UNEXPECTED,
                    cause = RedactedCause(this),
                )
        return PayInSubmissionState.Failed(
            cause = cause,
            fieldErrors = PayInRejectedFields.of(this),
            retryKey = retry.key.takeIf { cause.keepsTheKey(retry.reused) },
        )
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
        private val held: HeldKey?,
        private val startedAt: Long,
    ) {
        var key: String? = null
            private set

        /**
         * When the key this attempt sends was first reserved, which is [startedAt] for any key but a reused
         * one.
         *
         * A reused key keeps the reservation it already had. Restamping it here would start the window
         * again on every resend, so a key could be sent indefinitely and would eventually be one the service
         * no longer holds, which is the second charge the window exists to prevent.
         */
        var reservedAt: Long = startedAt
            private set

        /** Whether the key sent is the one held for this payment rather than the caller's or a new one. */
        var reused: Boolean = false
            private set

        /**
         * The key this attempt sends, in the order the caller's intent decides it.
         *
         * [supplied] first, because a caller that set a key is naming the attempt itself. Then the key held
         * for this payment, so a resend after an outcome nobody knows is the same request rather than a
         * second one. Otherwise a new key: a canceled or timed-out attempt may already have moved funds, and
         * an attempt with no key cannot be retried without risking a second charge.
         */
        fun reserve(supplied: String?): String {
            val chosen =
                when {
                    supplied != null -> supplied
                    held != null -> {
                        reservedAt = held.reservedAt
                        reused = true
                        held.key
                    }
                    else -> newIdempotencyKey()
                }
            key = chosen
            return chosen
        }
    }

    private companion object {
        const val REASON_UNEXPECTED = "The payment could not be submitted"

        /** How long a held key is still worth sending. See [stillWorthSending] for why it is short. */
        val HELD_KEY_WINDOW_NANOS: Long = TimeUnit.SECONDS.toNanos(90)
    }
}
