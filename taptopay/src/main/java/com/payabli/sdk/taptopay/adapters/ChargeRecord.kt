package com.payabli.sdk.taptopay.adapters

import com.payabli.sdk.core.network.PayabliJson
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/**
 * One payment, as the reader is asked for it.
 *
 * The reader's request has no field for an invoice number on this platform, so there is none here. It
 * reaches Payabli on the call that opened the payment.
 */
internal class ReaderCharge(
    val amount: BigDecimal,
    val merchantTransactionId: String,
    val merchantOrderId: String,
) {
    override fun toString(): String = "ReaderCharge"
}

/**
 * What is kept of the processor's answer, and forwarded to Payabli.
 *
 * A named subset, never a re-encoding: the same response carries the card, its holder, its expiry and its
 * security code, and forwarding a list of fields is what keeps those out of it.
 */
@Serializable
internal class ChargeRecord(
    val gatewayResponse: GatewayResponseRecord? = null,
    val paymentReceipt: PaymentReceiptRecord? = null,
    val cardNetwork: String? = null,
) {
    /** The JSON text the transaction client forwards. An object, which that client requires. */
    fun encoded(): String = PayabliJson.format.encodeToString(serializer(), this)
}

@Serializable
internal class GatewayResponseRecord(
    val transactionType: String? = null,
    val transactionState: String? = null,
    val transactionOrigin: String? = null,
    val gatewayTransactionId: String? = null,
    val gatewayName: String? = null,
    val gatewayOrderId: String? = null,
    val transactionProcessingDetails: TransactionProcessingRecord? = null,
)

@Serializable
internal class TransactionProcessingRecord(
    val orderId: String? = null,
    val transactionTimestamp: String? = null,
    val apiTraceId: String? = null,
    val clientRequestId: String? = null,
    val transactionId: String? = null,
)

@Serializable
internal class PaymentReceiptRecord(
    val processorResponseDetails: ProcessorResponseRecord? = null,
)

/**
 * How the processor answered.
 *
 * The account reference it also returns is not here: it is a durable handle on the cardholder's account.
 */
@Serializable
internal class ProcessorResponseRecord(
    val approvalStatus: String? = null,
    val approvalCode: String? = null,
    val responseCode: String? = null,
    val responseMessage: String? = null,
    val referenceNumber: String? = null,
    val schemeTransactionId: String? = null,
)
