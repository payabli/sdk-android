package com.payabli.sdk.taptopay.network

import com.payabli.sdk.taptopay.attestation.device.RedactedCause
import java.net.HttpURLConnection.HTTP_NOT_FOUND

/**
 * A refusal from a MoneyIn route a card-present charge uses, read from the v2 envelope's code, not the HTTP status:
 * `A` approves, `D` declines, anything else is a request or service problem. [reason] is the service's text: never
 * shown and never logged, because it can carry back what the request contained.
 */
internal sealed class TTPTransactionException(
    message: String,
    /** The envelope's `code`, a short fixed-vocabulary token. Safe to log. */
    val code: String?,
    val reason: String?,
    cause: Throwable? = null,
    val httpStatus: Int? = null,
) : Exception(message, cause) {
    /** Never [reason]: it is service text that can echo what was sent. */
    override fun toString(): String = "${javaClass.simpleName}(code=$code)"

    /**
     * The paypoint is not enabled for card-present payments.
     *
     * A 404 on these two routes cannot be a mistyped path, because both paths are constants here. So it is
     * read as the paypoint not being enabled, which is worth its own type because the remedy is the
     * opposite of every other failure on these routes: an account change, and no number of retries reaches
     * it.
     */
    class NotEnabled(
        code: String?,
    ) : TTPTransactionException(
            "the paypoint is not enabled for card-present payments",
            code = code,
            reason = null,
            httpStatus = HTTP_NOT_FOUND,
        )

    /** The payment was refused. A `D` code. */
    class Refused(
        code: String,
        reason: String?,
        httpStatus: Int?,
    ) : TTPTransactionException("the payment was refused", code, reason, httpStatus = httpStatus)

    /** The service did not approve and did not decline, so the problem is the request or the service. */
    class ServiceRejected(
        code: String,
        reason: String?,
        httpStatus: Int?,
    ) : TTPTransactionException("the service did not approve the transaction", code, reason, httpStatus = httpStatus)

    /**
     * The processor refused the card.
     *
     * Read from the reader's own answer rather than from an envelope, so [code] carries the gateway's
     * transaction state. Its own type because a refused card is the one failure here a merchant can act on
     * by asking for another card, and because it is the only one that arrives after the card has been read.
     */
    class CardRefused(
        state: String?,
    ) : TTPTransactionException("the card was refused", code = state, reason = null)

    /**
     * The reader answered with a state that names neither an approval nor a refusal.
     *
     * Neither outcome is claimed, which is the point: the payment may have been taken. The attempt keeps its
     * idempotency key, so whatever resolves it can still recognise it.
     */
    class OutcomeUnknown(
        state: String?,
    ) : TTPTransactionException("the payment outcome is not known", code = state, reason = null)

    /** An approval carrying none of the fields it is an approval for. */
    class Undecodable(
        cause: Throwable? = null,
        httpStatus: Int? = null,
    ) : TTPTransactionException(
            "the transaction response could not be read",
            code = null,
            reason = null,
            // Wrapped here rather than at the call sites, so no caller can forget, and matching
            // DeviceServiceException.Undecodable which does the same for the same reason. A decoder's own
            // message quotes the input it choked on - kotlinx appends the offending JSON - and a transaction
            // response body holds a paymentTransId and the processor's own fields. Redacting this class's
            // message buys nothing while a cause underneath it carries the body, because a crash reporter
            // renders the whole chain and the host's reporter is outside anything this SDK scrubs.
            cause = cause?.let { RedactedCause(it) },
            httpStatus = httpStatus,
        )
}
