package com.payabli.example.app.payment

import com.payabli.sdk.payin.payment.PayInSubmissionState
import com.payabli.sdk.payin.payment.PayabliPayInPaymentFlow

/**
 * Whether this flow is holding anything: a submission in flight, or an outcome nobody has taken yet.
 *
 * Both payment screens read it around a token recheck, which builds a session and replaces the flow they hold.
 * Replaced while it holds a request, the request still reaches the service and can move money; replaced while it
 * holds a terminal state the form has not consumed, the outcome reaches neither callback. Either way the form
 * observes a new idle flow and the screen offers Submit again.
 *
 * `Idle` is the handshake: the form delivers the outcome and consumes it, and the flow reads idle only once
 * nothing is owed.
 */
internal fun PayabliPayInPaymentFlow?.isBusy(): Boolean = this != null && state.value != PayInSubmissionState.Idle
