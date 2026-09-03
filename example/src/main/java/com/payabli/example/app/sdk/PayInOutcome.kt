package com.payabli.example.app.sdk

import com.payabli.example.app.demo.payment.PaymentError
import com.payabli.example.app.demo.payment.PaymentResult
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.payment.PayInSubmissionState

/**
 * What a submission ended as, in this app's own words.
 *
 * The form calls back with the SDK's own state; this is what the screens are handed instead, so a view model
 * holds nothing it would have to update when the SDK's state type changes.
 */
sealed class PayInOutcome {
    /** The service accepted it. */
    class Approved internal constructor(
        val result: PaymentResult,
    ) : PayInOutcome()

    /**
     * It did not go through.
     *
     * [diagnostic] is the failure's own classification and carries nothing from the wire, so it is the part
     * safe to record. [error] is displayable and is what a screen shows.
     */
    class Refused internal constructor(
        val error: PaymentError,
        val diagnostic: String,
        /**
         * Whether the idempotency key this attempt used is still the right one to send.
         *
         * True when the outcome is unknown, so a retry has to carry the same key for the service to recognise
         * the repeat, and true when nothing was sent because another attempt is in flight holding it. False
         * when the service answered: what the payer sends next is a different request.
         */
        val keepsItsIdempotencyKey: Boolean,
    ) : PayInOutcome()
}

internal fun PayInSubmissionState.Succeeded.toOutcome(): PayInOutcome.Approved =
    PayInOutcome.Approved(toPaymentResult())

/**
 * The same two shapes for a call that returns rather than publishing to the form's state.
 *
 * The state these calls do not publish is where a form reads `retryKey`, so the classification is made from
 * the failure's own code instead. It answers the same question: whether the request may have been carried
 * out, in which case what goes next has to be the same attempt rather than a new one.
 */
internal fun Result<PayInResult>.toOutcome(): PayInOutcome =
    fold(
        onSuccess = { PayInOutcome.Approved(it.toPaymentResult()) },
        onFailure = { failure ->
            PayInOutcome.Refused(
                error = failure.toPaymentError(),
                diagnostic = failure.toString(),
                keepsItsIdempotencyKey = failure.leavesOutcomeUnknown(),
            )
        },
    )

/**
 * Whether the request may have been carried out, so a retry has to carry the same key rather than a new one.
 *
 * The same five the SDK keeps a `retryKey` for: a cancellation and a network failure can both happen after
 * the bytes were written, a 5xx can follow work already done, a body that would not decode came from a
 * service that answered, and an unexpected error is unexamined by definition.
 *
 * Everything else was answered. A decline, a validation refusal and a rejected credential are outcomes, and
 * what is sent next is a different request.
 */
private fun Throwable.leavesOutcomeUnknown(): Boolean =
    (this as? PayabliException)?.code in
        setOf(
            PayabliErrorCode.USER_CANCELLED,
            PayabliErrorCode.NETWORK_ERROR,
            PayabliErrorCode.SERVER_ERROR,
            PayabliErrorCode.DECODING_ERROR,
            PayabliErrorCode.UNKNOWN,
        )

internal fun PayInSubmissionState.Failed.toOutcome(): PayInOutcome.Refused =
    PayInOutcome.Refused(
        error = toPaymentError(),
        diagnostic = cause.toString(),
        keepsItsIdempotencyKey = retryKey != null || cause is PayInException.AlreadySubmitting,
    )
