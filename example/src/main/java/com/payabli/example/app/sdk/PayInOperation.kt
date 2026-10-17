package com.payabli.example.app.sdk

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
    /** The payment this attempt names, for the screen that decides when to name another. */
    internal val paymentReference: String?
        get() =
            when (val it = operation) {
                is PayabliPayInOperation.Capture -> it.options.paymentReference
                is PayabliPayInOperation.Authorize -> it.options.paymentReference
                is PayabliPayInOperation.StoreMethod -> null
            }
}

/** The fee this app puts on a payment. `totalAmount` includes it. */
internal val DEMO_SERVICE_FEE: BigDecimal = BigDecimal("0.10")

/**
 * A capture of [amount] named [paymentReference], described as [identity]'s at [atMillis] so runs from several
 * devices stay attributable. [suppliesDemoCustomer] says whether it carries the customer number.
 */
fun capturePayment(
    paymentReference: String,
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
                paymentReference = paymentReference,
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
