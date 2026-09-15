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
 * [providerResponse] is JSON text forwarded to Payabli without being read here. **What it holds is the
 * implementation's to decide, and this interface does not bound it**: an implementation may forward
 * whatever the processor answered, card expiry and a minted token included. It is kept off `toString`,
 * out of every log field, and reported by size rather than content for that reason.
 *
 * The shipped adapter is narrower than the contract permits. `FiservAndroidCardReader` forwards a
 * re-encoded `ChargeRecord`, which is a named list of gateway identifiers and processor approval fields;
 * the card, its holder, its expiry and its security code are not among them. Read that as what this
 * implementation does rather than as what the type guarantees.
 */
internal class CardReadResult(
    val cardNetwork: String?,
    val providerResponse: String,
    val outcome: CardReadOutcome,
    /**
     * The implementation's own name for what [outcome] was read from, for diagnosis.
     *
     * Safe to log and safe to keep: a short fixed token from the processor's vocabulary, of the same kind as
     * [cardNetwork]. It carries no amount, no identifier and nothing of the card. Null where the
     * implementation had no state to read, which is itself one reason an outcome can be indeterminate.
     */
    val providerState: String?,
) {
    override fun toString(): String = "CardReadResult(cardNetwork=$cardNetwork, outcome=$outcome)"
}

/**
 * What the processor did with the card, as the implementation reads it.
 *
 * **A read that returned is not a payment that was taken.** The reader answers with a record for a refused
 * card as readily as for an approved one, so an implementation that cannot say which has to say so rather
 * than let the caller assume the first.
 *
 * [INDETERMINATE] is a third answer and not a polite refusal: it is for a state the implementation does not
 * recognise, and for one that names neither outcome. Resolving it needs the transaction read back, which is
 * not this call's to do, so what it buys here is that nothing reports such a payment as taken.
 */
internal enum class CardReadOutcome {
    APPROVED,
    DECLINED,
    INDETERMINATE,
}
