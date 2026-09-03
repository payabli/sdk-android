package com.payabli.sdk.taptopay.network

import com.payabli.sdk.taptopay.attestation.device.RedactedCause

/**
 * A refusal from one of the two MoneyIn routes a card-present charge uses.
 *
 * **These routes answer in the v2 envelope, where the code's first letter is the outcome.** An `A` is an
 * approval, a `D` is the payment being refused, and anything else is the service reporting a problem with
 * the request or with itself. The HTTP status agrees with that most of the time and not always, which is
 * why the envelope is read rather than the status.
 *
 * Module-local rather than new `PayabliErrorCode` cases, on the precedent
 * [com.payabli.sdk.taptopay.attestation.AttestationException] sets and states: that vocabulary is matched
 * string for string by the sibling SDK and is not this module's to widen. A genuine transport failure still
 * arrives as `PayabliException` from `PayabliHttpErrors`, so which type a caller catches says which layer
 * failed.
 *
 * [reason] is text this SDK did not author, so treat it as able to carry back anything the request
 * contained: displayable, and **never loggable**. That is how `PayabliException` treats the same field.
 */
internal sealed class TTPTransactionException(
    message: String,
    /** The envelope's `code`, a short fixed-vocabulary token. Safe to log. */
    val code: String?,
    val reason: String?,
    cause: Throwable? = null,
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
    class NotEnabled :
        TTPTransactionException(
            "the paypoint is not enabled for card-present payments",
            code = null,
            reason = null,
        )

    /** The payment was refused. A `D` code. */
    class Refused(
        code: String,
        reason: String?,
    ) : TTPTransactionException("the payment was refused", code, reason)

    /** The service did not approve and did not decline, so the problem is the request or the service. */
    class ServiceRejected(
        code: String,
        reason: String?,
    ) : TTPTransactionException("the service did not approve the transaction", code, reason)

    /** An approval carrying none of the fields it is an approval for. */
    class Undecodable(
        cause: Throwable? = null,
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
        )
}
