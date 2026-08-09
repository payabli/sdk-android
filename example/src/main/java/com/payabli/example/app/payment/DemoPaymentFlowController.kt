package com.payabli.example.app.payment

import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.form.PayInMethodType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Stands in for the payment SDK until it exists.
 *
 * It returns a result shaped like the real one, so every screen downstream — the result card, the
 * stored-method outcome, the transaction summary, the response JSON — renders real content and can
 * be reviewed today.
 *
 * Nothing here touches a card. There is no instrument in this file and no instrument in what it
 * returns, which is the same property the real flow has to have.
 *
 * @param stepDelayMillis zero in tests.
 */
class DemoPaymentFlowController(
    override val operation: PaymentOperation,
    private val stepDelayMillis: Long = DEFAULT_STEP_DELAY_MILLIS,
) : PaymentFlowController {
    override val setup: DemoFormSetup =
        when (operation) {
            PaymentOperation.StoreMethod -> DemoForms.storePaymentMethod()
            PaymentOperation.Capture -> DemoForms.capture()
        }

    private var counter = 0

    override suspend fun submit(values: PayInFormValues): Result<PaymentResult> {
        delay(stepDelayMillis)
        counter += 1
        return Result.success(
            when (operation) {
                PaymentOperation.StoreMethod -> storedMethodResult()
                PaymentOperation.Capture -> capturedResult(values.method)
            },
        )
    }

    private fun storedMethodResult(): PaymentResult {
        val id = "demo-method-%04d".format(counter)
        return PaymentResult(
            code = "1",
            reason = "Success",
            storedMethod =
                StoredMethod(
                    storedMethodId = id,
                    responseText = "Payment method saved",
                    resultText = "Approved",
                ),
            apiResponse =
                buildJsonObject {
                    put("responseText", JsonPrimitive("Payment method saved"))
                    put("isSuccess", JsonPrimitive(true))
                    put("responseData", JsonPrimitive(id))
                },
        )
    }

    private fun capturedResult(method: PayInMethodType): PaymentResult {
        val id = "demo-txn-%04d".format(counter)
        return PaymentResult(
            code = "1",
            reason = "Approved",
            explanation = "The payment was authorised and captured.",
            action = "None",
            transaction =
                Transaction(
                    paymentTransactionId = id,
                    gatewayTransactionId = "demo-gw-%04d".format(counter),
                    orderId = "demo-order-%04d".format(counter),
                    // The payer's choice, not the screen's default. It reads "card" for a bank
                    // account only if the form never says which instrument was filled.
                    method = method.wireName,
                    operation = "capture",
                    status = "Captured",
                    totalAmount = "1.10",
                    feeAmount = "0.10",
                    source = "android-example",
                ),
            apiResponse = capturedResponseJson(id),
        )
    }

    private fun capturedResponseJson(id: String): JsonObject =
        buildJsonObject {
            // Unsorted, so the sort ResponseJson applies on the way to the screen is visible.
            put("responseText", JsonPrimitive("Approved"))
            put("isSuccess", JsonPrimitive(true))
            put("authCode", JsonPrimitive("DEMO01"))
            put("responseData", JsonPrimitive(id))
        }

    companion object {
        const val DEFAULT_STEP_DELAY_MILLIS: Long = 700
    }
}
