package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
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
import kotlin.coroutines.cancellation.CancellationException

/**
 * One payment form's submission: what state it is in, and the one call that changes it.
 *
 * **The caller owns the scope.** Every entry point suspends, so whoever calls decides whether a capture dies
 * with the screen. Canceling does not un-charge a card.
 *
 * **One holder per form, and no singleton.** A holder keeps its terminal state — a result, or an exception
 * carrying the service's own wording — for as long as it lives, so its lifetime is a screen's. A host holds
 * it wherever its own screen state lives.
 *
 * [state] is a `StateFlow`, which replays its latest value, so a collector arriving after a configuration
 * change sees `Submitting` or the outcome immediately.
 */
internal class PayInSubmission(
    private val moneyIn: MoneyInClient,
    private val storage: TokenStorageClient,
    private val dispatcher: CoroutineDispatcher,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.NETWORK),
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
    ): PayInSubmissionState? =
        perform { retry ->
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
                        retry.key = operation.options.idempotencyKey
                        PayInSubmissionState.Succeeded.Payment(
                            moneyIn.capture(entryPoint, PayInRequest(method, operation.options), entered),
                        )
                    }

                is PayabliPayInOperation.Authorize ->
                    PayInFormInstrument.usePaymentMethod(values) { method ->
                        retry.key = operation.options.idempotencyKey
                        PayInSubmissionState.Succeeded.Payment(
                            moneyIn.authorize(entryPoint, PayInRequest(method, operation.options), entered),
                        )
                    }
            }
        }

    /**
     * Captures a transaction authorized earlier, in full or in part.
     *
     * Not a [PayabliPayInOperation]: the method was settled when the transaction was authorized, so no form is
     * read and no buffer is built. It shares the state and the single flight, so a screen that authorizes and
     * then captures reports both through one place.
     */
    suspend fun captureAuthorized(request: PayInAuthorizedRequest): PayInSubmissionState? =
        perform { retry ->
            retry.key = request.idempotencyKey
            PayInSubmissionState.Succeeded.Payment(moneyIn.captureAuthorized(request))
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
     */
    private suspend fun perform(call: suspend (RetryKey) -> PayInSubmissionState): PayInSubmissionState? {
        if (!inFlight.tryLock()) {
            logger.debug(LogField.safe("event", "payin_submission_already_in_flight")) {
                "a submission is already in flight, so this one was refused"
            }
            return null
        }
        val retry = RetryKey()
        sink.value = PayInSubmissionState.Submitting
        var outcome: PayInSubmissionState? = null
        try {
            outcome = withContext(dispatcher) { call(retry) }
        } catch (cancellation: CancellationException) {
            // Rethrown: a coroutine that swallows its own cancellation stops being cancellable. The state still
            // records it, because the charge may have landed and the retry key is what a second attempt needs.
            outcome = PayInSubmissionState.Failed(PayInException.Interrupted(retry.key))
            throw cancellation
        } catch (failure: Exception) {
            outcome = failure.asFailed()
        } finally {
            // Neither line suspends, so both run on the canceled path as they do on any other.
            outcome?.let { sink.value = it }
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
    private fun Exception.asFailed(): PayInSubmissionState.Failed =
        PayInSubmissionState.Failed(
            cause =
                this as? PayabliException
                    ?: PayabliGenericException(
                        PayabliErrorCode.UNKNOWN,
                        REASON_UNEXPECTED,
                        cause = RedactedCause(this),
                    ),
            fieldErrors = PayInRefusedFields.of(this),
        )

    /**
     * The idempotency key of the request that went out, once there is one.
     *
     * Read only when a submission is canceled, which is the one outcome that cannot say whether the service
     * acted. The caller's own request carries the key, so it is known only once that has been built.
     */
    private class RetryKey {
        var key: String? = null
    }

    private companion object {
        const val REASON_UNEXPECTED = "The payment could not be submitted"
    }
}
