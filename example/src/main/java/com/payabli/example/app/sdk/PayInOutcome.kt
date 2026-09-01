package com.payabli.example.app.sdk

import com.payabli.example.app.demo.payment.PaymentError
import com.payabli.example.app.demo.payment.PaymentResult
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
 * A void carries no idempotency key back to the screen: the key belongs to the attempt the SDK minted, and
 * the screen has no way to resend that same one, so a retry here is a new request either way.
 */
internal fun Result<PayInResult>.toOutcome(): PayInOutcome =
    fold(
        onSuccess = { PayInOutcome.Approved(it.toPaymentResult()) },
        onFailure = { failure ->
            PayInOutcome.Refused(
                error = failure.toPaymentError(),
                diagnostic = failure.toString(),
                keepsItsIdempotencyKey = false,
            )
        },
    )

internal fun PayInSubmissionState.Failed.toOutcome(): PayInOutcome.Refused =
    PayInOutcome.Refused(
        error = toPaymentError(),
        diagnostic = cause.toString(),
        keepsItsIdempotencyKey = retryKey != null || cause is PayInException.AlreadySubmitting,
    )
