package com.payabli.example.app.sdk

import com.payabli.example.app.demo.payment.PaymentError
import com.payabli.example.app.demo.payment.PaymentResult
import com.payabli.example.app.demo.payment.StoredMethod
import com.payabli.example.app.demo.payment.Transaction
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.payin.PayInFormSuccess
import com.payabli.sdk.payin.model.PayInResult
import com.payabli.sdk.payin.model.PayInStoredMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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
        reason = reason,
        explanation = explanation,
        action = action,
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
                    feeAmount = it.feeAmount?.toPlainString(),
                    source = it.connectorName,
                )
            },
        apiResponse = transaction.asJson(code, reason, explanation, action),
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
private fun com.payabli.sdk.payin.model.PayInTransaction?.asJson(
    code: String,
    reason: String?,
    explanation: String?,
    action: String?,
): JsonObject =
    buildJsonObject {
        put("code", JsonPrimitive(code))
        // The three beside the code. The card is headed "Exactly what came back", so a field the SDK exposes
        // and this omits is the screen contradicting its own heading.
        reason?.let { put("reason", JsonPrimitive(it)) }
        explanation?.let { put("explanation", JsonPrimitive(it)) }
        action?.let { put("action", JsonPrimitive(it)) }
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
 * The failure in this app's words, for a failure the SDK handed over as an exception: the form's
 * refusal arm and a call that throws both reach this. Reason and detail are the displayable pair, so a
 * decline reads as a decline; anything not a `PayabliException` reads as unexpected.
 */
internal fun Throwable.toPaymentError(): PaymentError =
    when (this) {
        is PayabliException -> PaymentError.Payabli(reason = reason, detail = detail)
        // Not from the SDK's own paths, so its text is the only thing there is to say.
        else -> PaymentError.Unexpected(this::class.java.name)
    }
