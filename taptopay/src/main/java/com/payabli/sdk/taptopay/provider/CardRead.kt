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
 * [providerResponse] is what an implementation kept of the processor's answer, as JSON text, forwarded to
 * Payabli without being read. **What an implementation puts in it is what decides how sensitive it is**, and
 * the contract permits anything the processor answered, so it is kept off `toString` and out of every log
 * field, and the client reports its size rather than its content. The shipped adapter forwards a named list
 * of processor identifiers and approval fields, and the card, its holder, its expiry and its security code
 * are not among them.
 */
internal class CardReadResult(
    val cardNetwork: String?,
    val providerResponse: String,
) {
    override fun toString(): String = "CardReadResult(cardNetwork=$cardNetwork)"
}
