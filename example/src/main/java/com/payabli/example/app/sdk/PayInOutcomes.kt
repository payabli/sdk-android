package com.payabli.example.app.sdk

import com.payabli.example.app.demo.payment.FailureKind
import com.payabli.example.app.demo.payment.PaymentError
import com.payabli.example.app.demo.payment.PaymentResult
import com.payabli.example.app.demo.payment.StoredMethod
import com.payabli.example.app.demo.payment.Transaction
import com.payabli.sdk.core.model.PayabliErrorCategory
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.payin.PayInFormSuccess
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.model.PayInStoredMethod

/**
 * The SDK's outcome in this app's own words.
 *
 * One direction only, and one place. The screens keep their own [PaymentResult] and [PaymentError] so the
 * demo's cards, summaries and navigation are unchanged by which SDK version produced them, and no screen
 * reached through this boundary names an SDK type. `demo/simple` does not come through here.
 *
 * **No instrument crosses this boundary**, because none is in what the SDK returns.
 */
internal fun PayInFormSuccess.toPaymentResult(): PaymentResult =
    when (this) {
        is PayInFormSuccess.Payment -> result.toPaymentResult()
        is PayInFormSuccess.Method -> storedMethod.toPaymentResult()
    }

internal fun PayInResult.toPaymentResult(): PaymentResult =
    PaymentResult(
        code = code,
        transaction =
            transaction?.let {
                Transaction(
                    paymentTransactionId = it.paymentTransId,
                    gatewayTransactionId = it.gatewayTransId,
                    orderId = it.orderId,
                    method = it.method,
                    // The SDK reports what the transaction is, not which call produced it, and this screen
                    // only ever captures.
                    operation = "capture",
                    status = it.transStatus?.toString(),
                    // As the API returned them. A payment amount reformatted on its way to a screen is how a
                    // display comes to disagree with what the service returned.
                    totalAmount = it.totalAmount?.toPlainString(),
                    feeAmount = it.feeAmount?.toPlainString(),
                    source = it.connectorName,
                )
            },
    )

/** A stored method carries no result code, so only the identifier crosses. */
private fun PayInStoredMethod.toPaymentResult(): PaymentResult =
    PaymentResult(
        code = "",
        storedMethod = StoredMethod(storedMethodId = storedMethodId),
    )

/**
 * The failure in this app's words, for a failure the SDK handed over as an exception: the form's refusal arm and a
 * call that throws both reach this. Only the category and the code cross, so no screen can show the error's text.
 * A payment whose outcome is unknown keeps its cause's category, so the wrapper is read first.
 */
internal fun Throwable.toPaymentError(): PaymentError =
    when (this) {
        is PayInException.Unsettled -> PaymentError(FailureKind.OUTCOME_UNKNOWN, code.toString())
        is PayabliException ->
            PaymentError(
                kind =
                    when (category) {
                        PayabliErrorCategory.DECLINED -> FailureKind.DECLINED
                        PayabliErrorCategory.RETRY_LATER -> FailureKind.RETRY_LATER
                        PayabliErrorCategory.OUTCOME_UNKNOWN -> FailureKind.OUTCOME_UNKNOWN
                        else -> FailureKind.OTHER
                    },
                code = code.toString(),
            )
        else -> PaymentError(FailureKind.OTHER, this::class.java.name)
    }
