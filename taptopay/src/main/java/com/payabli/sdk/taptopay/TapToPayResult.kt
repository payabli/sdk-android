package com.payabli.sdk.taptopay

/**
 * What a completed payment leaves the caller with.
 *
 * [paymentTransId] is the identifier the payment was opened under and the one it is reconciled by.
 * [cardNetwork] is a brand name and carries nothing else about the card.
 */
public class TapToPayResult(
    public val paymentTransId: String,
    public val cardNetwork: String?,
) {
    override fun toString(): String = "TapToPayResult(paymentTransId=$paymentTransId)"
}
