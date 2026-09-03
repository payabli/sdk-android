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
        perform(operation.event, entryPoint, onReserved) { retry ->
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
        perform(TelemetryEvents.PAYIN_CAPTURE_COMPLETED, entryPoint, publishes = false) { retry ->
            val key = retry.hold(request.idempotencyKey)
            PayInSubmissionState.Succeeded.Payment(moneyIn.captureAuthorized(request, key))
        }

    /** Reverses a transaction. Reads no form, and takes only what identifies the transaction. */
    suspend fun void(
        entryPoint: String,
        transId: String,
        idempotencyKey: String?,
    ): PayInSubmissionState? =
        perform(TelemetryEvents.PAYIN_VOID_COMPLETED, entryPoint, publishes = false) { retry ->
            val key = retry.hold(idempotencyKey)
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
        val retry = RetryKey()
        val startedAt = System.nanoTime()
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
            outcome = failure.asFailed(retry.key)
        } finally {
            // Nothing here suspends, so all of it runs on the canceled path as it does on any other. That is
            // what makes an abandoned payment countable: it is the one outcome nobody is left to report.
            outcome?.let {
                if (publishes) sink.value = it
                report(event, outcomeOf(it), codeOf(it), startedAt, entryPoint)
            }
            inFlight.unlock()
        }
        return outcome
    }

    /**
     * The failure, with the field it blamed.
     *
     * Anything that is not a [PayabliException] is a defect in this SDK, and arrives as
     * [PayabliErrorCode.UNKNOWN] carrying its type and its frames but not its message: a message from inside a
     * body writer or a serializer can quote what it was given.
     */
    private fun Exception.asFailed(attemptKey: String?): PayInSubmissionState.Failed {
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
            retryKey = attemptKey.takeIf { cause.code.leavesOutcomeUnknown },
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
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - it).toString(),
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
    private inner class RetryKey {
        var key: String? = null
            private set

        /**
         * The key this attempt sends: [supplied] when the caller set one, otherwise a new one.
         *
         * Minted here rather than left absent because a canceled or timed-out attempt may already have moved
         * funds, and a caller with no key cannot retry without risking a second charge. One key per attempt, so
         * a retry the caller decides to make is the same request and a second payment is a second key.
         */
        fun reserve(supplied: String?): String = (supplied ?: newIdempotencyKey()).also { key = it }

        /**
         * The caller's own key, recorded without minting one in its place.
         *
         * For an operation that publishes nothing: a minted key reaches the caller through
         * [PayInSubmissionState.Failed.retryKey] on the state, and a caller reading a returned `Result` never
         * sees that state. Minting there would produce a key that makes the attempt retryable in principle and
         * is unreadable in practice, so an ambiguous failure would look recoverable and would not be. Absent,
         * the caller supplies one or accepts that a retry is a new attempt, which is what its own request type
         * already documents.
         */
        fun hold(supplied: String?): String? = supplied.also { key = it }
    }

    private companion object {
        const val REASON_UNEXPECTED = "The payment could not be submitted"
    }
}
