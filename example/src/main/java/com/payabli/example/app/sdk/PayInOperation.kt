package com.payabli.example.app.sdk

import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInStoreOptions
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.payin.payment.PayabliPayInOperation
import java.math.BigDecimal

/** What a tap on the form does, built here so a screen names none of the request types. */
class PayInOperation internal constructor(
    internal val operation: PayabliPayInOperation,
) {
    /** The key this attempt carries, for the screen that decides when to mint another. */
    internal val idempotencyKey: String?
        get() =
            when (val it = operation) {
                is PayabliPayInOperation.Capture -> it.options.idempotencyKey
                is PayabliPayInOperation.Authorize -> it.options.idempotencyKey
                is PayabliPayInOperation.StoreMethod -> null
            }
}

/**
 * A capture of the demo amount under [idempotencyKey].
 *
 * Without a key the service cannot recognise a repeat, so a submission whose outcome is unknown cannot be
 * retried. One key per attempt, kept while that attempt's outcome is unknown and replaced once the service
 * has answered, which is the screen's rule and lives with the screen.
 *
 * The amount is fixed because the form this configures collects none: a real integration takes it from the
 * order it is charging for.
 */
fun capturePayment(idempotencyKey: String): PayInOperation =
    PayInOperation(
        PayabliPayInOperation.Capture(
            PayInTransactionOptions(
                paymentDetails = PayInPaymentDetails(totalAmount = BigDecimal("1.10"), serviceFee = BigDecimal("0.10")),
                orderId = "android-example",
                idempotencyKey = idempotencyKey,
                // A paypoint can refuse a payment that names no customer it can identify, and answers before
                // the payer's own fields are read, so the request does not depend on which of them they filled.
                forceCustomerCreation = true,
            ),
        ),
    )

/** Storing what the payer entered, so a later transaction can charge it without the details again. */
fun storePaymentMethod(): PayInOperation =
    PayInOperation(
        PayabliPayInOperation.StoreMethod(PayInStoreOptions(forceCustomerCreation = true)),
    )
