package com.payabli.example.app.sdk

import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInFailure
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.model.PayInStoredMethod
import com.payabli.sdk.payin.model.PayInTransaction
import com.payabli.sdk.payin.payment.PayInSubmissionState
import java.math.BigDecimal

// The outcomes the SDK can hand this app, built once. Written per test file, the screens' tests and the
// mapping's tests were free to disagree about what an approval looks like, which is how a mapping test comes
// to pass against a shape the service never sends.

/** A stored method as the SDK reports one: an identifier a later transaction can charge. */
internal fun storedMethodOutcome() =
    PayInSubmissionState.Succeeded.Method(
        PayInStoredMethod(
            storedMethodId = "tok-77",
            methodReferenceId = "tok-77-225810",
            customerId = 88L,
            resultCode = 1,
            resultText = "Approved",
        ),
    )

/**
 * A capture the service approved, carrying the transaction a capture screen exists to show.
 *
 * An approval with no transaction is a different case, which the capture screen calls a failure.
 */
internal fun capturedPaymentOutcome() =
    PayInSubmissionState.Succeeded.Payment(
        PayInResult(
            code = "A0000",
            transaction =
                PayInTransaction(
                    paymentTransId = "101-abc",
                    gatewayTransId = "gtw-9",
                    orderId = "order-1",
                    method = "card",
                    transStatus = 1,
                    paypointId = 42,
                    totalAmount = BigDecimal("1.10"),
                    netAmount = BigDecimal("1.00"),
                    connectorName = "fiserv",
                    customerId = 7,
                ),
        ),
    )

/** A decline, which is the failure a payer meets most and the one whose wording reaches the screen. */
internal fun refusedOutcome() =
    PayInSubmissionState.Failed(
        PayInException.Refused(
            PayInFailure(
                code = "D0001",
                reason = "Insufficient funds",
                explanation = "Try another card",
                action = "r",
                httpStatus = 200,
            ),
        ),
    )

/**
 * A step one that already succeeded, for tests about what a screen does afterwards.
 *
 * `payments` is null: a JVM test cannot build a `PayabliPayInPaymentFlow`, whose test constructor is internal
 * to `:payin`. Nothing that uses this submits, so the outcomes are handed to the view model directly.
 */
internal fun readyStartup() =
    PayInStartup {
        PayInStartup.Started(text = "returned a token", isReady = true, payments = null)
    }
