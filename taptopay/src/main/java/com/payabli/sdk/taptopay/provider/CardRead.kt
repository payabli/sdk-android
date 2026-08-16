package com.payabli.sdk.taptopay.provider

import java.math.BigDecimal

/**
 * What one contactless payment needs, once Payabli has opened it.
 *
 * [merchantTransactionId] is the identifier the payment was opened under, and the reconciliation reads the
 * outcome back from the processor by it. [merchantOrderId] carries the same value: the two are separate
 * fields at the processor and this SDK has one identifier to put in both.
 */
internal class CardReadRequest(
    val amount: BigDecimal,
    val merchantTransactionId: String,
    val merchantOrderId: String,
    val merchantInvoiceNumber: String?,
)

/**
 * What the reader answers with.
 *
 * [providerResponse] is the processor's own response, as JSON text, and it is forwarded to Payabli without
 * being read. **It carries the card's expiry and any token the processor minted**, which is why it is kept
 * off `toString` and out of every log field, and why the client reports its size rather than its content.
 */
internal class CardReadResult(
    val cardNetwork: String?,
    val providerResponse: String,
) {
    override fun toString(): String = "CardReadResult(cardNetwork=$cardNetwork)"
}
