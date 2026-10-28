package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.model.PayabliAttempt
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.model.PayabliValidationException
import com.payabli.sdk.core.model.leavesOutcomeUnknown
import com.payabli.sdk.core.network.PayabliSendMark
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
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInRequest
import com.payabli.sdk.payin.model.PayInStoreRequest
import com.payabli.sdk.payin.model.PayInTransaction
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.payin.model.RedactedCause
import com.payabli.sdk.payin.model.leavesThePaymentOpen
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One payment form's submission and its [state], which replays its latest value. Every call suspends in the
 * caller's scope, and canceling does not un-charge a card. One per form: its state lives as long as a screen,
 * and the keys it holds outlive it in [PayInKeyStore].
 */
internal class PayInSubmission(
    private val moneyIn: MoneyInClient,
    private val storage: TokenStorageClient,
    private val dispatcher: CoroutineDispatcher,
    private val environment: PayabliEnvironment,
    private val newIdempotencyKey: () -> String,
    private val elapsedRealtimeNanos: () -> Long,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.NETWORK),
    private val session: TelemetrySessionContext? = null,
    private val newPaymentReference: () -> String = ::mintedPaymentReference,
) {
    /**
     * The single flight, held for the whole call.
     *
     * [Mutex.tryLock] is one step. A boolean read and then written is two, and two callers can both pass the
     * read.
     */
    private val inFlight = Mutex()

    private val keys = PayInKeyStore(logger)

    private val sink = MutableStateFlow<PayInSubmissionState>(PayInSubmissionState.Idle)

    /** The last published outcome whose request the service may have acted on. */
    @Volatile
    private var sentOutcome: PayInSubmissionState? = null

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
    ): PayInSubmissionState? {
        val reference = operation.transactionOptions?.let(::referenceOf)
        return perform(
            operation.event,
            entryPoint,
            onReserved,
            movesMoney = operation.movesMoney,
            retry =
                RetryKey(
                    entryPoint,
                    operation.holding?.let { paymentOf(it, reference) },
                    reference,
                    operation.declaresRetry,
                ),
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
                        val key = retry.reserve(PayInPaymentDescription.of(method, operation.options))
                        PayInSubmissionState.Succeeded.Payment(
                            moneyIn.capture(entryPoint, PayInRequest(method, operation.options), entered, key),
                        )
                    }

                is PayabliPayInOperation.Authorize ->
                    PayInFormInstrument.usePaymentMethod(values) { method ->
                        val key = retry.reserve(PayInPaymentDescription.of(method, operation.options))
                        PayInSubmissionState.Succeeded.Payment(
                            moneyIn.authorize(entryPoint, PayInRequest(method, operation.options), entered, key),
                        )
                    }
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
    ): PayInSubmissionState? {
        val reference = referenceOf(request.options)
        return perform(
            TelemetryEvents.PAYIN_CAPTURE_COMPLETED,
            entryPoint,
            publishes = false,
            retry =
                RetryKey(
                    entryPoint,
                    paymentOf(PayInRoutes.CAPTURE, reference),
                    reference,
                    claimsHeldKey = request.options.attempt == PayabliAttempt.Retry,
                ),
        ) { retry ->
            val key = retry.reserve(PayInPaymentDescription.of(request.paymentMethod, request.options))
            PayInSubmissionState.Succeeded.Payment(
                moneyIn.capture(entryPoint, request, PayInEnteredDetails.NONE, key),
            )
        }
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
    ): PayInSubmissionState? {
        val reference = referenceOf(request.options)
        return perform(
            TelemetryEvents.PAYIN_AUTHORIZE_COMPLETED,
            entryPoint,
            publishes = false,
            retry =
                RetryKey(
                    entryPoint,
                    paymentOf(PayInRoutes.AUTHORIZE, reference),
                    reference,
                    claimsHeldKey = request.options.attempt == PayabliAttempt.Retry,
                ),
        ) { retry ->
            val key = retry.reserve(PayInPaymentDescription.of(request.paymentMethod, request.options))
            PayInSubmissionState.Succeeded.Payment(
                moneyIn.authorize(entryPoint, request, PayInEnteredDetails.NONE, key),
            )
        }
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
            retry = RetryKey(entryPoint, payment = null),
        ) {
            PayInSubmissionState.Succeeded.Method(storage.storeMethod(entryPoint, request.instrument, request.options))
        }

    /**
     * Captures a transaction authorized earlier, in full or in part. Reads no form.
     *
     * @param entryPoint the partner integration point the payment is attributed to.
     * @param request the authorization to capture, in full or in part.
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
            retry =
                request.transId.trim().let {
                    RetryKey(entryPoint, "${PayInRoutes.CAPTURE_AUTHORIZED}:$it", transaction = it)
                },
        ) { retry ->
            val key = retry.reserve()
            PayInSubmissionState.Succeeded.Payment(moneyIn.captureAuthorized(request, key))
        }

    /**
     * Reverses a transaction. Reads no form, and takes only what identifies the transaction.
     *
     * @param entryPoint the partner integration point the reversal is attributed to.
     * @param transId the transaction to reverse.
     * @return the terminal state, or null when a submission was already in flight.
     */
    suspend fun void(
        entryPoint: String,
        transId: String,
    ): PayInSubmissionState? =
        perform(
            TelemetryEvents.PAYIN_VOID_COMPLETED,
            entryPoint,
            publishes = false,
            retry = transId.trim().let { RetryKey(entryPoint, "${PayInRoutes.VOID}:$it", transaction = it) },
        ) { retry ->
            val key = retry.reserve()
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
            report(event, TelemetryProperties.Outcome.APPROVED, null, null, startedAt, entryPoint, fromForm = false)
            Result.success(transaction)
        } catch (cancellation: CancellationException) {
            report(
                event,
                TelemetryProperties.Outcome.INTERRUPTED,
                null,
                cancellation.javaClass.simpleName,
                startedAt,
                entryPoint,
                fromForm = false,
            )
            throw cancellation
        } catch (failure: Exception) {
            val cause =
                failure as? PayabliException
                    ?: PayabliGenericException(
                        PayabliErrorType.UNKNOWN,
                        REASON_UNEXPECTED,
                        cause = RedactedCause(failure),
                    )
            report(
                event,
                outcomeOf(cause),
                cause.type,
                failure.javaClass.simpleName,
                startedAt,
                entryPoint,
                fromForm = false,
            )
            Result.failure(cause)
        }
    }

    /**
     * Whether [outcome] is the published outcome of a request the service may have acted on, which is when a form
     * lets the instrument go.
     *
     * @param outcome a state read from [state].
     * @return true when it may have; false otherwise, including for a state this did not publish.
     */
    fun sent(outcome: PayInSubmissionState): Boolean = outcome === sentOutcome

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
     * @param retry the payment a key is held against, and whether this call may claim the held one.
     * @param call the operation itself, handed its retry key.
     * @return the terminal state, or null when a submission was already in flight.
     */
    private suspend fun perform(
        event: String,
        entryPoint: String? = null,
        onReserved: (Boolean) -> Unit = {},
        publishes: Boolean = true,
        movesMoney: Boolean = true,
        retry: RetryKey,
        call: suspend (RetryKey) -> PayInSubmissionState,
    ): PayInSubmissionState? {
        // Answered before the first suspension, so a caller starting this undispatched learns whether the single
        // flight was taken rather than inferring it from a state that is published before the guard is released.
        if (!inFlight.tryLock()) {
            onReserved(false)
            logger.debug(LogField.safe("event", "payin_submission_already_in_flight")) {
                "a submission is already in flight, so this one was refused"
            }
            report(event, TelemetryProperties.Outcome.REFUSED_LOCALLY, null, null, null, entryPoint, publishes)
            return null
        }
        // An outcome nothing has read yet stays until `reset`, so a taken payment keeps its record. A caller that
        // does not publish overwrites nothing, so it is not held to the form's state and headless calls can follow.
        if (publishes && sink.value != PayInSubmissionState.Idle) {
            inFlight.unlock()
            onReserved(false)
            logger.debug(LogField.safe("event", "payin_submission_outcome_unacknowledged")) {
                "an outcome has not been acknowledged, so this submission was refused"
            }
            report(event, TelemetryProperties.Outcome.REFUSED_LOCALLY, null, null, null, entryPoint, publishes)
            return null
        }
        onReserved(true)
        val startedAt = elapsedRealtimeNanos()
        // Set by the transport as it hands the request over, so a failure before that is known not to have left.
        val sendMark = PayabliSendMark()
        if (publishes) sink.value = PayInSubmissionState.Submitting
        var outcome: PayInSubmissionState? = null
        // The failing type's name, read where the failure is caught: the wrapping below replaces the type
        // and keeps no copy of its name.
        var failingType: String? = null
        try {
            outcome = withContext(dispatcher + sendMark) { call(retry) }
        } catch (cancellation: CancellationException) {
            // Rethrown, or the coroutine stops being cancellable. Open only when a send of this payment may have run:
            // a resent key's earlier send, or this one past the hand-off; never for an operation moving no money.
            failingType = cancellation.javaClass.simpleName
            val interrupted = PayInException.Interrupted()
            val unknown = movesMoney && retry.key != null && (retry.reused || sendMark.mayHaveLeft)
            outcome =
                PayInSubmissionState.Failed(
                    if (unknown) PayInException.Unsettled(interrupted, retry.reference) else interrupted,
                )
            throw cancellation
        } catch (failure: Exception) {
            failingType = failure.javaClass.simpleName
            outcome = failure.asFailed(retry, movesMoney, sendMark.mayHaveLeft)
        } finally {
            // Nothing here suspends, so all of it runs on the canceled path as it does on any other. That is
            // what makes an abandoned payment countable: it is the one outcome nobody is left to report.
            val finished = outcome
            if (finished != null) {
                retry.mayHaveLeft = sendMark.mayHaveLeft
                retry.payment?.let { settle(it, finished, retry) }
                if (publishes) {
                    // Before the publish, so a form reading the outcome reads whether it was sent with it.
                    sentOutcome = finished.takeIf { sendMark.mayHaveLeft }
                    sink.value = finished
                }
                report(event, outcomeOf(finished), codeOf(finished), failingType, startedAt, entryPoint, publishes)
            } else {
                // An error that is not an exception ends the send too, or its reference stays refused for the life of
                // the process.
                val payment = retry.payment
                val key = retry.key
                if (payment != null && key != null) {
                    val ending = if (sendMark.mayHaveLeft) SendEnding.MayHaveArrived(retry.sentAt) else SendEnding.Ended
                    keys.end(retry.entryPoint, environment, payment, key, ending)
                }
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
        val key = retry.key ?: return
        val ending =
            when {
                failure == null || failure.answersThePayment() -> SendEnding.Answered
                // Ahead of the general case: a refused resend keeps the key without restarting its clock.
                retry.reused && failure.type == PayabliErrorType.RETRY_REFUSED -> SendEnding.RefusedAsRepeat
                // A send that never left starts no new key's clock and restarts no held one's.
                failure.type.leavesOutcomeUnknown && retry.mayHaveLeft -> SendEnding.MayHaveArrived(retry.sentAt)
                else -> SendEnding.Ended
            }
        keys.end(retry.entryPoint, environment, payment, key, ending)
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
     * The failure, with the field it blamed. A non-[PayabliException] arrives as [PayabliErrorType.UNKNOWN],
     * keeping its type and frames but not its message; the service refusing a key this holder resent is
     * [PayabliErrorType.RETRY_REFUSED], which proves only that the earlier attempt arrived.
     *
     * @param retry the key this attempt carried.
     * @param movesMoney whether the operation can take a payment.
     * @param mayHaveLeft whether this attempt's request passed the hand-off.
     * @return the failed state, with the field it blamed.
     */
    private fun Exception.asFailed(
        retry: RetryKey,
        movesMoney: Boolean,
        mayHaveLeft: Boolean,
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
        // A fresh key's send that ended before the hand-off ran nowhere, so its failure is reported as it is.
        val unknown = movesMoney && (retry.reused || mayHaveLeft) && cause.leavesTheOutcomeUnknown(retry.reused)
        return PayInSubmissionState.Failed(
            cause = if (unknown) PayInException.Unsettled(cause, retry.reference) else cause,
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
            else -> type.leavesThePaymentOpen
        }

    /**
     * Reports how one submission ended, over the span a payment incident asks about and no per-request
     * record can answer. Carries no instrument, no payer and no amount. [code] is the classification
     * the failure already published to the caller, which is a fixed set.
     *
     * @param event the telemetry event naming the operation.
     * @param outcome how the submission ended.
     * @param code the failure's classification, or null when it succeeded.
     * @param failureType the failing type's simple name as it reached this holder, or null when it succeeded.
     * @param startedAt when the submission began, or null when it never started.
     * @param entryPoint the integration point the request was sent to, or null for the session's.
     * @param fromForm whether a form started the submission.
     */
    private fun report(
        event: String,
        outcome: String,
        code: PayabliErrorType?,
        failureType: String?,
        startedAt: Long?,
        entryPoint: String?,
        fromForm: Boolean,
    ) {
        // The entry point the request was sent to, which a capability can be pointed at independently of the
        // one the session was configured with. Reporting the session's would file it under another merchant.
        val attributed = if (entryPoint == null) session else session?.forEntryPoint(entryPoint)

        if (attributed != null) {
            TelemetryRecorders.recordFor(
                attributed,
                event,
            ) { measurements(outcome, code, failureType, startedAt, fromForm) }
        } else {
            TelemetryRecorders.record(event) { measurements(outcome, code, failureType, startedAt, fromForm) }
        }
    }

    private fun measurements(
        outcome: String,
        code: PayabliErrorType?,
        failureType: String?,
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
            failureType?.let { put(TelemetryProperty.FAILURE_TYPE.key, it) }
            // A submission refused before anything is sent has nothing to time.
            if (outcome != TelemetryProperties.Outcome.REFUSED_LOCALLY) {
                startedAt?.let {
                    put(
                        TelemetryProperty.DURATION_MS.key,
                        TimeUnit.NANOSECONDS.toMillis(elapsedRealtimeNanos() - it).toString(),
                    )
                }
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
                cause.type == PayabliErrorType.INVALID_CONFIGURATION ||
                cause.type == PayabliErrorType.RETRY_NOT_SAME_PAYMENT ||
                cause.type == PayabliErrorType.PAYMENT_REFERENCE_OPEN
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
        entryPoint: String,
        val payment: String?,
        val reference: String? = null,
        private val claimsHeldKey: Boolean = true,
        private val transaction: String? = null,
    ) {
        /** The entry point trimmed, as the request carries it, so padding does not move the key to another partition. */
        val entryPoint: String = entryPoint.trim()

        var key: String? = null
            private set

        /** When this send reserved its key, whether or not the key was already held. */
        var sentAt: Long = 0
            private set

        /** Whether the key sent is the one held for this payment rather than a new one. */
        var reused: Boolean = false
            private set

        /** Whether this attempt's request may have reached the service, read once the attempt has ended. */
        var mayHaveLeft: Boolean = true

        /** The payment this attempt describes, or null on a route that names a transaction. */
        var description: PayInPaymentDescription? = null
            private set

        /**
         * The key this attempt sends: the key held for this payment, otherwise a new one. The held key's age
         * is read here, where the key is sent, not where the call began: this clock counts device sleep, so a
         * check taken earlier can be arbitrarily stale by the time the request leaves.
         *
         * Refused before anything is sent: a blank payment reference, any send while a held key of the same payment,
         * or of a capture or void of the same transaction, has a send under way, a new payment naming one whose key
         * is still held, and a declared retry describing a different payment from the held one. The held key stays.
         *
         * @param description the payment this attempt describes, or null on a route that names a transaction.
         * @return the key this attempt sends.
         */
        fun reserve(description: PayInPaymentDescription? = null): String {
            if (description != null && payment == null) {
                throw PayInException.InvalidInput(FIELD_PAYMENT_REFERENCE, REASON_BLANK_REFERENCE)
            }
            val now = elapsedRealtimeNanos()
            sentAt = now
            this.description = description
            if (payment == null) {
                return newIdempotencyKey().also { key = it }
            }
            val reserved =
                keys.reserve(
                    entryPoint,
                    environment,
                    payment,
                    // A capture and a void of one transaction share one send at a time.
                    transaction ?: payment,
                    now,
                    claimsHeldKey,
                    description,
                    newIdempotencyKey,
                )
            reused = reserved.reused
            // Carried with the key, so the payment it was minted for outlives a sweep of its record.
            this.description = reserved.description
            key = reserved.key
            return reserved.key
        }
    }

    /**
     * The payment reference [options] names, trimmed, or a new one when it names none. Null for a blank one,
     * which names no payment and is refused before anything is sent.
     */
    private fun referenceOf(options: PayInTransactionOptions): String? {
        val given = options.paymentReference ?: return newPaymentReference()
        return given.trim().ifEmpty { null }
    }

    /** What names a capture or an authorization: its route and its reference, or nothing for a blank reference. */
    private fun paymentOf(
        route: String,
        reference: String?,
    ): String? = reference?.let { "$route:$it" }

    internal companion object {
        const val REASON_UNEXPECTED = "The payment could not be submitted"
        const val REASON_RETRY_REFUSED = "Retry refused (409)"
        const val FIELD_PAYMENT_REFERENCE = "paymentReference"
        const val REASON_BLANK_REFERENCE = "The paymentReference cannot be blank"
    }
}

/** A new payment reference: a random UUID, lowercase. */
@OptIn(ExperimentalUuidApi::class)
internal fun mintedPaymentReference(): String = Uuid.random().toString()
