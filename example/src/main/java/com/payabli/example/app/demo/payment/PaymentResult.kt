package com.payabli.example.app.demo.payment

import kotlinx.serialization.json.JsonObject

/** A stored instrument, identified by a token. Never carries the instrument itself. */
data class StoredMethod(
    val storedMethodId: String,
    val responseText: String,
    val resultText: String,
) {
    /**
     * Its own, because `data class` would synthesize one over the identifier and both service strings.
     *
     * The identifier charges a card, so it is a credential rather than a label, and the two strings are the
     * service's own words. A synthesized `toString` reaches assertion failures, exception messages and crash
     * reports without passing through anything that redacts.
     */
    override fun toString(): String = "StoredMethod"
}

/**
 * A completed transaction.
 *
 * Amounts are strings, as the API returned them. This is a readout, and reformatting a value on its
 * way to a screen is how a display comes to disagree with the response beside it.
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
 * One type for both screens: a stored method fills [storedMethod], a capture fills [transaction],
 * and the shared fields at the top are present either way.
 */
data class PaymentResult(
    val code: String,
    val reason: String? = null,
    val explanation: String? = null,
    val action: String? = null,
    val storedMethod: StoredMethod? = null,
    val transaction: Transaction? = null,
    val apiResponse: JsonObject? = null,
) {
    /**
     * The code alone, for the reason the SDK's own result type gives.
     *
     * [reason], [explanation] and [action] are the service's words, and the SDK documents them as displayable
     * and never loggable because an approval can quote what was submitted. They now arrive from the service
     * rather than being built here, so a synthesized `toString` over this class would carry them into every
     * message that stringifies it.
     */
    override fun toString(): String = "PaymentResult(code=$code)"
}

/**
 * What the payment form reported instead.
 *
 * Sealed, so a screen handles both shapes. [Payabli] is a failure the platform described and
 * [Unexpected] is everything else. Keeping them apart stops a transport error being displayed as
 * though the gateway had declined something.
 */
sealed interface PaymentError {
    val displayMessage: String

    data class Payabli(
        val reason: String,
        val detail: String? = null,
    ) : PaymentError {
        override val displayMessage: String
            get() =
                // The detail only earns a line when it adds something. An empty detail, or one that
                // repeats the reason, produces two identical sentences stacked on a card.
                if (detail.isNullOrEmpty() || detail == reason) reason else "$reason\n$detail"
    }

    data class Unexpected(
        val text: String,
    ) : PaymentError {
        override val displayMessage: String get() = text
    }
}

/**
 * Carries a [PaymentError] out of a `Result.failure`.
 *
 * A controller reports failure as a `Result`, and a bare exception arrives at the screens as text.
 * This keeps the reason and the detail apart, so a decline reads as a decline rather than as
 * whatever a message happened to say.
 */
class PaymentFailure(
    val error: PaymentError,
) : Exception(error.displayMessage)
