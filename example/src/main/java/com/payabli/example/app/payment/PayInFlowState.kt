package com.payabli.example.app.payment

import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInPaymentFlow

/**
 * Whether a submission is still in flight on this flow.
 *
 * Both payment screens read it twice around a token recheck, which builds a session and replaces the flow they
 * hold: before starting one, and again before the replacement lands, because a payment can begin while the
 * recheck is suspended. Replaced mid-payment, the request already sent still reaches the service and can move
 * money, while the form observes a new idle flow: the outcome arrives nowhere and the screen offers Submit again.
 */
internal fun PayabliPayInPaymentFlow?.isSubmitting(): Boolean = this?.state?.value is PayInSubmissionState.Submitting
