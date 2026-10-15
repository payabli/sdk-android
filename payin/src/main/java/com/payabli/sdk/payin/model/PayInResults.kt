package com.payabli.sdk.payin.model

import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.RedactedFailure
import java.math.BigDecimal

/**
 * A method the service stored, identified for later use.
 *
 * A later transaction charges [storedMethodId] as [method], by passing both to [PayInPaymentMethod.Stored].
 * [storedMethodId] is never absent: a claimed success carrying none is refused as unreadable.
 */
public class PayInStoredMethod(
    public val storedMethodId: String,
    public val method: PayInStoredMethodType,
    public val methodReferenceId: String?,
    public val customerId: Long?,
    public val resultCode: Int?,
    public val resultText: String?,
) {
    /**
     * The identifier charges a card, so it is a credential rather than a label, and it is not in a message.
     *
     * @return the type name alone.
     */
    override fun toString(): String = "PayInStoredMethod"
}

/**
 * A transaction, as an operation answered it or as [com.payabli.sdk.payin.PayabliPayIn.readTransaction] read it
 * back.
 *
 * **An authorization code, an AVS result and a security-code result are not here.** No operation's answer
 * carries them, and a read does not fill them. Declaring them would give a caller three fields that are always
 * null and imply the SDK went looking.
 *
 * @property transStatus the transaction's status number, as it is reported on the wire.
 * @property paypointId the paypoint that took it, by its own key rather than by the entry point a caller configured.
 * @property netAmount what is left after fees, where the paypoint splits them out.
 * @property connectorName which processor took it.
 */
public class PayInTransaction(
    public val paymentTransId: String?,
    public val gatewayTransId: String?,
    public val orderId: String?,
    public val method: String?,
    public val transStatus: Int?,
    public val paypointId: Long?,
    public val totalAmount: BigDecimal?,
    public val netAmount: BigDecimal?,
    public val feeAmount: BigDecimal?,
    public val surchargeFee: BigDecimal?,
    public val connectorName: String?,
    public val customerId: Long?,
) {
    /** What became of it, or null when no status number was reported. [transStatus] keeps the number itself. */
    public val status: PayInTransactionStatus?
        get() = transStatus?.let(PayInTransactionStatus::of)

    /**
     * Never the amount or the customer: an identifier is enough to correlate, and the rest is data.
     *
     * @return whether a transaction identifier is present.
     */
    override fun toString(): String = "PayInTransaction(hasTransId=${paymentTransId != null})"
}

/**
 * What the service said when it approved a transaction.
 *
 * [code] is the unified response code, which begins with `A` for the approved family. It is kept rather than
 * reduced to a boolean because the specific code is what a caller reconciles against.
 *
 * [reason], [explanation] and [action] are the words beside it. An approval is not always the same act: a
 * void answers `A0003` and calls itself canceled, where a capture answers `A0000`. A caller showing the
 * outcome needs what the service called it, and reducing every approval to its code throws that away.
 *
 * @property reason displayable, and never loggable: the same rule [PayInFailure.reason] carries, for the
 *   same reason.
 */
public class PayInResult(
    public val code: String,
    public val reason: String?,
    public val explanation: String?,
    public val action: String?,
    public val transaction: PayInTransaction?,
) {
    /**
     * The code alone. The three fields above are service text and do not belong in a message or a log.
     *
     * @return the code alone.
     */
    override fun toString(): String = "PayInResult(code=$code)"
}

/**
 * Why the service refused, in the shape it refuses in.
 *
 * The same type covers a declined transaction, a service error and a refused stored method, because a caller
 * does the same three things with any of them: show [reason], act on [action], and record [code]. Which of
 * the three it was is the exception carrying it.
 *
 * @property code the unified response code, `D`-prefixed for a decline and `E`-prefixed for an error.
 * @property reason displayable, and never loggable: some of these can quote what was submitted.
 * @property paymentTransId the transaction this failure belongs to, or null where the service named none.
 *   Carried for every non-approved answer, so a service error names its transaction as a decline does: a
 *   caller that means to reconcile one reads this whichever kind of failure it holds.
 */
public class PayInFailure(
    public val code: String?,
    public val reason: String?,
    public val explanation: String?,
    public val action: String?,
    public val httpStatus: Int?,
    public val paymentTransId: String? = null,
) {
    override fun toString(): String = "PayInFailure(code=$code, httpStatus=$httpStatus)"
}

/**
 * Why a PayIn call did not produce a result.
 *
 * A [PayabliException] subclass, so a caller already handling the SDK's transport failures handles these in
 * the same `catch` and reads the same [PayabliException.type].
 */
public sealed class PayInException(
    type: PayabliErrorType,
    reason: String,
    detail: String? = null,
    cause: Throwable? = null,
) : PayabliException(type, reason, detail, cause) {
    /**
     * A value this module refused before sending it.
     *
     * [field] carries the wire spelling for the field at fault, where there is one, so a form can mark it
     * without a second mapping. [reason] is displayable and carries no submitted value.
     */
    public class InvalidInput(
        public val field: String?,
        reason: String,
    ) : PayInException(PayabliErrorType.VALIDATION_ERROR, reason) {
        override fun toString(): String = "PayInException.InvalidInput(field=$field)"
    }

    /** The service refused the transaction or the stored method. */
    public class Refused(
        public val failure: PayInFailure,
    ) : PayInException(
            PayabliErrorType.PAYMENT_DECLINED,
            failure.reason ?: DEFAULT_REFUSED_REASON,
            failure.explanation,
        ) {
        override fun toString(): String = "PayInException.Refused(code=${failure.code})"
    }

    /**
     * The transaction could not be processed, which is not the same as being refused.
     *
     * The unified codes separate the two: a `D` is a decline, and an `E` is an error about the request or on
     * the far side. Reporting one as the other puts decline wording in front of a payer whose card was never
     * asked, and hides a condition a caller might retry.
     */
    public class ServiceError(
        public val failure: PayInFailure,
    ) : PayInException(
            PayabliErrorType.SERVER_ERROR,
            failure.reason ?: DEFAULT_SERVICE_ERROR_REASON,
            failure.explanation,
        ) {
        override fun toString(): String = "PayInException.ServiceError(code=${failure.code})"
    }

    /**
     * A 2xx that could not be read as either an approval or a refusal.
     *
     * A refusal and an unreadable answer call for different actions: one is about the payment, the other is
     * about this SDK or the service.
     */
    public class Undecodable(
        cause: Throwable? = null,
    ) : PayInException(
            PayabliErrorType.DECODING_ERROR,
            DEFAULT_UNDECODABLE_REASON,
            cause = cause?.let(::RedactedCause),
        ) {
        override fun toString(): String = "PayInException.Undecodable"
    }

    /**
     * The submission was canceled before it finished.
     *
     * A `Result` call never receives this: cancellation is rethrown, so the call does not return. It reaches
     * a form through the state it publishes.
     *
     * **On a submission that moves money, the key marks how far it got.** Canceled once the key was
     * reserved, the request may have been carried out, so the form publishes [Unsettled] carrying this as
     * its cause and a held key is resent by the next call naming the same transaction. Canceled before the
     * reservation, nothing was sent and nothing is held, so this is the whole of the outcome.
     *
     * **Storing a method reserves no key, and this is the whole of the outcome there whenever it arrives.**
     * A cancellation can follow a request that was already sent, so no key is not proof that nothing left
     * the device. It is not reported as unresolved because a store moves no money: three identical bodies
     * return three different identifiers, so a repeat is not recognizable and there is nothing for a key to
     * settle. A store whose fate is in doubt is settled by reading the entry point's stored methods back.
     */
    public class Interrupted : PayInException(PayabliErrorType.USER_CANCELLED, DEFAULT_INTERRUPTED_REASON) {
        override fun toString(): String = "PayInException.Interrupted"
    }

    /**
     * The request may have been carried out, and the outcome is not known. That is the whole of what this
     * reports, and it is true wherever it is raised.
     *
     * **Whether repeating the call is recognised as the same attempt is the member's to say, not this
     * type's.** Only a call naming a transaction has an identity to hold a key against, so only those
     * members promise a repeat is recognised, and each states its own bound. Elsewhere a repeat is
     * recognised under a key the caller supplied and not otherwise.
     *
     * Acting on this promptly is what matters either way, because no bound is this SDK's to extend: a key
     * the service no longer recognises is carried out as a new payment whoever chose it. What came back the
     * first time is not repeated, so a caller that needs the outcome reads the transaction back with
     * [com.payabli.sdk.payin.PayabliPayIn.readTransaction].
     *
     * [PayabliException.type] is [cause]'s own, so a caller branching on it reads what went wrong as well
     * as that it is unresolved. It is taken from [cause] rather than accepted beside it, because two
     * sources for one classification can disagree and a caller cannot tell which it is holding. [cause]
     * names the failing type and withholds its message, and it names the type that failed rather than this
     * SDK's stand-in for it: a failure already carrying a redaction keeps the one it has.
     */
    public class Unsettled(
        cause: PayabliException,
    ) : PayInException(
            cause.type,
            DEFAULT_UNSETTLED_REASON,
            detail = cause.reason,
            cause = cause.redactedOnce(),
        ) {
        /**
         * The payment this failure belongs to, or null where the service named none.
         *
         * Null is not proof that nothing was taken: a request whose answer was lost names nothing, and that
         * is the case this type exists for. It is the only handle to a payment that exists, so a caller that
         * means to reconcile one holds this.
         *
         * **Carried here because the cause cannot carry it.** The cause is a redaction, which keeps a type
         * and its frames and no reference to what it stood for, so a failure that named its transaction
         * would lose the name by being reported as unresolved — at the one moment this type tells a caller
         * to read the transaction back.
         */
        public val paymentTransId: String? = cause.namedTransaction()

        override fun toString(): String = "PayInException.Unsettled(code=${type.wireName})"
    }

    /**
     * A submission was already in flight, so this one was refused and nothing was sent.
     *
     * A sequencing mistake by the caller rather than anything that came back, which is why it carries
     * [PayabliErrorType.INVALID_CONFIGURATION] and no failure from the wire.
     */
    public class AlreadySubmitting :
        PayInException(PayabliErrorType.INVALID_CONFIGURATION, DEFAULT_ALREADY_SUBMITTING_REASON) {
        override fun toString(): String = "PayInException.AlreadySubmitting"
    }

    public companion object {
        internal const val DEFAULT_REFUSED_REASON: String = "The payment was refused"
        internal const val DEFAULT_SERVICE_ERROR_REASON: String = "The payment could not be processed"
        internal const val DEFAULT_UNDECODABLE_REASON: String = "The response could not be read"
        internal const val DEFAULT_INTERRUPTED_REASON: String = "The payment was interrupted before it finished"
        internal const val DEFAULT_UNSETTLED_REASON: String =
            "The payment may have been taken, and its outcome is not known"
        internal const val DEFAULT_ALREADY_SUBMITTING_REASON: String = "A submission is already in flight"
    }
}

/**
 * The redaction [PayabliException.cause] already is, or a new one standing in for the failure itself:
 * redacting an existing one a second time would name this SDK's own wrapper and carry the frames of the
 * site that built it. `:core` attaches a raw cause on some paths, redacted here for the first time.
 *
 * @return the existing redaction, or a new one standing in for the failure itself.
 */
private fun PayabliException.redactedOnce(): Throwable = cause?.takeIf { it is RedactedFailure } ?: RedactedCause(this)

/**
 * The transaction this failure named, where it named one. The identifier only, and none of the rest of
 * [PayInFailure]: `reason`, `explanation` and `action` are displayable and never loggable, and a type
 * whose work is to withhold a message does not republish them in another slot.
 *
 * @return the transaction a service error named, or null where none was named.
 */
private fun PayabliException.namedTransaction(): String? =
    (this as? PayInException.ServiceError)?.failure?.paymentTransId

/**
 * Carries a cause's type without its message.
 *
 * A decode failure's message quotes the input, which for these bodies can be a card number. `:core` draws the
 * same boundary for the same reason; this is that pattern rather than a new one.
 */
internal class RedactedCause(
    cause: Throwable,
) : Throwable("${cause.javaClass.name} (message withheld)"),
    RedactedFailure {
    init {
        // The frames are the whole diagnostic value: which serializer failed, in which file, at which line.
        // Dropping them leaves every decode failure pointing at this constructor.
        stackTrace = cause.stackTrace
    }
}
