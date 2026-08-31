package com.payabli.example.app.sdk

import com.payabli.example.app.demo.sample.DemoCustomerSetting
import com.payabli.example.app.demo.sample.SampleIdentity
import com.payabli.sdk.payin.model.PayInCustomerData
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
 * The fee this app puts on a payment, and the reason the form's amount row is not the charge.
 *
 * `totalAmount` includes it, so the row reads the part before it. Read by both the request and the form, which
 * is what keeps the figure a payer is shown equal to the one being charged.
 */
internal val DEMO_SERVICE_FEE: BigDecimal = BigDecimal("0.10")

/**
 * A capture of [amount] under [idempotencyKey], ordered and described as [identity]'s at [atMillis].
 *
 * Without a key the service cannot recognise a repeat, so a submission whose outcome is unknown cannot be
 * retried. One key per attempt, kept while that attempt's outcome is unknown and replaced once the service
 * has answered, which is the screen's rule and lives with the screen.
 *
 * The amount is the caller's because the form this configures collects none: a real integration takes it from
 * the order it is charging for. The order identifier and the description name the device and the moment, which
 * is what keeps a run over several devices at once attributable in a transaction list.
 *
 * @param suppliesDemoCustomer whether the request carries the customer number, which
 *   [DemoCustomerSetting] decides. The payer is named either way: the form's first name, last name and
 *   billing email are written over whatever this configures.
 */
fun capturePayment(
    idempotencyKey: String,
    amount: BigDecimal,
    identity: SampleIdentity,
    atMillis: Long,
    suppliesDemoCustomer: Boolean,
): PayInOperation =
    PayInOperation(
        PayabliPayInOperation.Capture(
            PayInTransactionOptions(
                paymentDetails = PayInPaymentDetails(totalAmount = amount, serviceFee = DEMO_SERVICE_FEE),
                // The number the form does not collect, which is what attaches every payment from this device to
                // one customer record. A form value would win over this one, and the capture form has no such
                // box. [DemoCustomerSetting] says what the other position of the switch does.
                customerData =
                    PayInCustomerData(customerNumber = identity.customerNumber)
                        .takeIf { suppliesDemoCustomer },
                orderId = identity.orderId(atMillis),
                orderDescription = identity.note("capture"),
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
