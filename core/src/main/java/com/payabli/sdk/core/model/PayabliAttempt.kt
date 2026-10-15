package com.payabli.sdk.core.model

/**
 * Whether a payment request is a new payment or a retry of one whose outcome is not known.
 *
 * Only the host knows which it is: two requests for the same amount look the same to the SDK either way.
 * A request that declares nothing is a new payment.
 */
public enum class PayabliAttempt {
    /** A payment of its own. It is sent under a new idempotency key, so the service treats it as a separate payment. */
    NewPayment,

    /**
     * Another try at the most recent payment whose outcome is not known. It is sent under that payment's
     * idempotency key, so the service recognises it and does not charge twice. If no such payment is held, or
     * the service would no longer recognise its key, it is sent under a new key.
     *
     * A retry describes the same payment: one that describes another is refused as
     * [PayabliErrorType.RETRY_NOT_SAME_PAYMENT] and not sent. A host that keeps more than one payment open sets
     * its own idempotency key on each.
     */
    Retry,
}
