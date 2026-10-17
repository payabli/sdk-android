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
     *
     * @property isOutcomeUnknown whether the service may have applied the request without the answer arriving.
     */
    class Refused internal constructor(
        val error: PaymentError,
        val diagnostic: String,
        /**
         * Whether the payment reference this attempt named still names the payment to retry: true when the outcome
         * is unknown or nothing was sent because another attempt is in flight, false once the service answered.
         */
        val keepsItsPaymentReference: Boolean,
        val isOutcomeUnknown: Boolean = false,
    ) : PayInOutcome()
}

internal fun PayInSubmissionState.Succeeded.toOutcome(): PayInOutcome.Approved =
    PayInOutcome.Approved(toPaymentResult())

/**
 * The same two shapes for a call that returns rather than publishing to the form's state.
 *
 * The SDK says which failures left the request's fate open by raising [PayInException.Unsettled], so this
 * reads that rather than classifying the failure itself. One rule, in the SDK, where it can be maintained
 * against the codes it names.
 */
internal fun Result<PayInResult>.toOutcome(): PayInOutcome =
    fold(
        onSuccess = { PayInOutcome.Approved(it.toPaymentResult()) },
        onFailure = { failure ->
            PayInOutcome.Refused(
                error = failure.toPaymentError(),
                diagnostic = failure.toString(),
                keepsItsPaymentReference =
                    failure is PayInException.Unsettled || failure is PayInException.AlreadySubmitting,
                isOutcomeUnknown = failure is PayInException.Unsettled,
            )
        },
    )

internal fun PayInSubmissionState.Failed.toOutcome(): PayInOutcome.Refused =
    PayInOutcome.Refused(
        error = toPaymentError(),
        diagnostic = cause.toString(),
        keepsItsPaymentReference =
            cause is PayInException.Unsettled || cause is PayInException.AlreadySubmitting,
        isOutcomeUnknown = cause is PayInException.Unsettled,
    )
