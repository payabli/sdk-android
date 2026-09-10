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
    /**
     * Presence, never the identifier itself, matching [com.payabli.sdk.payin.model.PayInTransaction] on the
     * card-not-present side.
     *
     * A `toString` reaches assertion failures, exception messages and crash reports without passing through
     * the logger, where a resolved transaction id is not a field the SDK records. Printing it here would be
     * a second way out for the value the logging rule keeps in.
     */
    override fun toString(): String = "TapToPayResult(hasPaymentTransId=${paymentTransId.isNotEmpty()})"
}
