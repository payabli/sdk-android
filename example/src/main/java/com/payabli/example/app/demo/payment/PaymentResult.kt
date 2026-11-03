package com.payabli.example.app.demo.payment

/** A stored instrument, identified by a token. Never carries the instrument itself. */
data class StoredMethod(
    val storedMethodId: String,
) {
    /**
     * Its own, because `data class` would synthesize one over the identifier, which charges a card, so it is a
     * credential rather than a label. A synthesized `toString` reaches assertion failures, exception messages and
     * crash reports without passing through anything that redacts.
     */
    override fun toString(): String = "StoredMethod"
}

/**
 * A completed transaction.
 *
 * Amounts are strings, as the API returned them. This is a readout, and reformatting a value on its
 * way to a screen is how a display comes to disagree with what the service returned.
 */
data class Transaction(
    val paymentTransactionId: String?,
    val gatewayTransactionId: String?,
    val orderId: String?,
    val method: String?,
    val operation: String?,
    val status: String?,
    val totalAmount: String?,
    val feeAmount: String?,
    val source: String?,
) {
    /** An identifier is enough to correlate a row with a run. The amounts and the rest are data. */
    override fun toString(): String = "Transaction(hasTransactionId=${paymentTransactionId != null})"
}

/**
 * What the payment form produced.
 *
 * One type for both screens: a stored method fills [storedMethod], a capture fills [transaction], and [code] is
 * present either way. The service's own text is not carried: a screen shows identifiers on a success.
 */
data class PaymentResult(
    val code: String,
    val storedMethod: StoredMethod? = null,
    val transaction: Transaction? = null,
) {
    /** The code alone, so the stored method's identifier stays out of anything that stringifies this. */
    override fun toString(): String = "PaymentResult(code=$code)"
}

/** Which of the category's messages a failure shows. */
enum class FailureKind { DECLINED, RETRY_LATER, OUTCOME_UNKNOWN, OTHER }

/**
 * What the payment form reported instead.
 *
 * A screen shows the message for [kind] and never the error's own text, which is the service's and can repeat
 * what was submitted. [code] goes to the diagnostics log for whoever reads it to look up.
 *
 * @property kind which message the screen shows.
 * @property code the error's catalog code, or the class name of a failure the SDK did not raise.
 */
data class PaymentError(
    val kind: FailureKind,
    val code: String,
)

/** The fixed text each screen shows for a failure, by what was being done. */
object FailureText {
    /**
     * @param kind the failure's kind.
     * @return the text for a payment that failed.
     */
    fun payment(kind: FailureKind): String =
        when (kind) {
            FailureKind.DECLINED -> "The payment was declined. Try another payment method."
            FailureKind.RETRY_LATER -> "The payment couldn't be completed. Please try again."
            FailureKind.OUTCOME_UNKNOWN -> "This payment may have gone through. Check before paying again."
            FailureKind.OTHER -> "The payment couldn't be completed."
        }

    /**
     * A save moves no money, so its texts never say a payment was declined or may have gone through.
     *
     * @param kind the failure's kind.
     * @return the text for a payment method that wasn't saved.
     */
    fun save(kind: FailureKind): String =
        when (kind) {
            FailureKind.DECLINED -> "This payment method couldn't be saved. Try another one."
            FailureKind.RETRY_LATER -> "The payment method couldn't be saved. Please try again."
            FailureKind.OUTCOME_UNKNOWN, FailureKind.OTHER -> "The payment method couldn't be saved."
        }

    /**
     * @param kind the failure's kind.
     * @return the text for a void that failed.
     */
    fun void(kind: FailureKind): String =
        when (kind) {
            FailureKind.OUTCOME_UNKNOWN -> "This void may have gone through. Read the transaction before voiding again."
            FailureKind.RETRY_LATER -> "The void couldn't be completed. Please try again."
            FailureKind.DECLINED, FailureKind.OTHER -> "The void couldn't be completed."
        }

    /**
     * A terminal action is a charge, or a step before one, so only a charge's unknown outcome says a payment.
     *
     * @param kind the failure's kind.
     * @return the text for a terminal action that failed.
     */
    fun terminal(kind: FailureKind): String =
        when (kind) {
            FailureKind.DECLINED -> "The payment was declined. Try another payment method."
            FailureKind.RETRY_LATER -> "That couldn't be completed. Please try again."
            FailureKind.OUTCOME_UNKNOWN -> "This payment may have gone through. Check before charging again."
            FailureKind.OTHER -> "That couldn't be completed."
        }

    /**
     * @param kind the failure's kind.
     * @return the text for a read that failed.
     */
    fun read(kind: FailureKind): String =
        when (kind) {
            FailureKind.RETRY_LATER -> "The transaction couldn't be read. Please try again."
            else -> "The transaction couldn't be read."
        }
}
