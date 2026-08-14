package com.payabli.example.app.sdk

import com.payabli.example.app.demo.payment.PaymentError
import com.payabli.example.app.demo.payment.PaymentResult
import com.payabli.example.app.demo.payment.StoredMethod
import com.payabli.example.app.demo.payment.Transaction
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.model.PayInStoredMethod
import com.payabli.sdk.payin.payment.PayInSubmissionState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The SDK's outcome in this app's own words.
 *
 * One direction only, and one place. The screens keep their own [PaymentResult] and [PaymentError] so the
 * demo's cards, summaries and navigation are unchanged by which SDK version produced them, and nothing in the
 * UI layer names an SDK type.
 *
 * **No instrument crosses this boundary**, because none is in what the SDK returns.
 */
internal fun PayInSubmissionState.Succeeded.toPaymentResult(): PaymentResult =
    when (this) {
        is PayInSubmissionState.Succeeded.Payment -> result.toPaymentResult()
        is PayInSubmissionState.Succeeded.Method -> storedMethod.toPaymentResult()
    }

private fun PayInResult.toPaymentResult(): PaymentResult =
    PaymentResult(
        code = code,
        reason = transaction?.transStatus?.let { "Status $it" },
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
                    // display comes to disagree with the response beside it.
                    totalAmount = it.totalAmount?.toPlainString(),
                    feeAmount = it.netAmount?.let { net -> it.totalAmount?.subtract(net)?.toPlainString() },
                    source = it.connectorName,
                )
            },
        apiResponse = transaction.asJson(code),
    )

private fun PayInStoredMethod.toPaymentResult(): PaymentResult =
    PaymentResult(
        code = resultCode?.toString() ?: "",
        reason = resultText,
        storedMethod =
            StoredMethod(
                storedMethodId = storedMethodId,
                responseText = resultText.orEmpty(),
                resultText = resultText.orEmpty(),
            ),
        apiResponse =
            buildJsonObject {
                put("isSuccess", JsonPrimitive(true))
                put("resultCode", JsonPrimitive(resultCode))
                resultText?.let { put("resultText", JsonPrimitive(it)) }
                customerId?.let { put("customerId", JsonPrimitive(it)) }
            },
    )

/**
 * The response card's content, rebuilt from the fields the SDK exposes.
 *
 * The SDK decodes the body and does not keep it, so this is a rendering of what came back rather than the
 * bytes that came back. The demo says so on the screen.
 */
private fun com.payabli.sdk.payin.model.PayInTransaction?.asJson(code: String): JsonObject =
    buildJsonObject {
        put("code", JsonPrimitive(code))
        this@asJson?.let { transaction ->
            transaction.paymentTransId?.let { put("paymentTransId", JsonPrimitive(it)) }
            transaction.gatewayTransId?.let { put("gatewayTransId", JsonPrimitive(it)) }
            transaction.orderId?.let { put("orderId", JsonPrimitive(it)) }
            transaction.method?.let { put("method", JsonPrimitive(it)) }
            transaction.transStatus?.let { put("transStatus", JsonPrimitive(it)) }
            transaction.totalAmount?.let { put("totalAmount", JsonPrimitive(it.toPlainString())) }
            transaction.connectorName?.let { put("connectorName", JsonPrimitive(it)) }
        }
    }

/**
 * The failure in this app's words.
 *
 * [PayabliException.reason] and [PayabliException.detail] are the displayable pair the SDK documents, and
 * `PaymentError.Payabli` already keeps them apart, so a decline reads as a decline rather than as whatever a
 * message happened to say. Anything that is not a `PayabliException` cannot come from the SDK's own paths and
 * reads as unexpected.
 */
internal fun PayInSubmissionState.Failed.toPaymentError(): PaymentError =
    PaymentError.Payabli(reason = cause.reason, detail = cause.detail)
