package com.payabli.sdk.payin.model

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import java.math.BigDecimal

/** A method the service stored, identified for later use. */
public class PayInStoredMethod(
    /** The identifier a later transaction charges. */
    public val storedMethodId: String?,
    public val methodReferenceId: Long?,
    public val customerId: Long?,
    public val resultCode: Int?,
    public val resultText: String?,
) {
    /** Identifiers only, and not the text, which the service may echo request data into. */
    override fun toString(): String = "PayInStoredMethod(hasId=${storedMethodId != null})"
}

/**
 * A transaction the service accepted.
 *
 * **An authorisation code, an AVS result and a security-code result are not here.** The record a v2 approval
 * carries is the service's detailed transaction record, and it holds none of the three: they exist only in the
 * older response shape this SDK does not use. Declaring them would give a caller three fields that are null on
 * every approval and imply the SDK went looking.
 */
public class PayInTransaction(
    public val paymentTransId: String?,
    public val gatewayTransId: String?,
    public val orderId: String?,
    public val method: String?,
    /** The service's own status number for the transaction. */
    public val transStatus: Int?,
    /** The paypoint that took it, which is the service's key rather than the entry point a caller configured. */
    public val paypointId: Long?,
    public val totalAmount: BigDecimal?,
    /** What is left after fees, where the paypoint splits them out. */
    public val netAmount: BigDecimal?,
    /** Which processor took it. */
    public val connectorName: String?,
    public val customerId: Long?,
) {
    /** Never the amount or the customer: an identifier is enough to correlate, and the rest is data. */
    override fun toString(): String = "PayInTransaction(hasTransId=${paymentTransId != null})"
}

/**
 * What the service said when it approved a transaction.
 *
 * [code] is the unified response code, which begins with `A` for the approved family. It is kept rather than
 * reduced to a boolean because the specific code is what a caller reconciles against.
 */
public class PayInResult(
    public val code: String,
    public val transaction: PayInTransaction?,
) {
    override fun toString(): String = "PayInResult(code=$code)"
}

/**
 * Why the service refused, in the shape it refuses in.
 *
 * The same type covers a declined transaction, a service error and a refused stored method, because a caller
 * does the same three things with any of them: show [reason], act on [action], and record [code]. Which of
 * the three it was is the exception carrying it.
 */
public class PayInFailure(
    /** The unified response code, `D`-prefixed for a decline and `E`-prefixed for an error. */
    public val code: String?,
    /** Displayable, and never loggable: the service echoes submitted values into some of these. */
    public val reason: String?,
    public val explanation: String?,
    public val action: String?,
    public val httpStatus: Int?,
) {
    override fun toString(): String = "PayInFailure(code=$code, httpStatus=$httpStatus)"
}

/**
 * Why a PayIn call did not produce a result.
 *
 * A [PayabliException] subclass, so a caller already handling the SDK's transport failures handles these in
 * the same `catch` and reads the same [PayabliException.code].
 */
public sealed class PayInException(
    code: PayabliErrorCode,
    reason: String,
    detail: String? = null,
    cause: Throwable? = null,
) : PayabliException(code, reason, detail, cause) {
    /**
     * A value this module refused before sending it.
     *
     * [field] carries the service's own spelling for the field at fault, where there is one, so a form can
     * mark it without a second mapping. [reason] is displayable and carries no submitted value.
     */
    public class InvalidInput(
        public val field: String?,
        reason: String,
    ) : PayInException(PayabliErrorCode.VALIDATION_ERROR, reason) {
        override fun toString(): String = "PayInException.InvalidInput(field=$field)"
    }

    /** The service refused the transaction or the stored method. */
    public class Refused(
        public val failure: PayInFailure,
    ) : PayInException(
            PayabliErrorCode.PAYMENT_DECLINED,
            failure.reason ?: DEFAULT_REFUSED_REASON,
            failure.explanation,
        ) {
        override fun toString(): String = "PayInException.Refused(code=${failure.code})"
    }

    /**
     * The service could not process the transaction, which is not the same as refusing it.
     *
     * The unified codes separate the two: a `D` is a decline, and an `E` is an error the service raised
     * about the request or about itself. Reporting one as the other puts decline wording in front of a
     * payer whose card was never asked, and hides a condition a caller might retry.
     */
    public class ServiceError(
        public val failure: PayInFailure,
    ) : PayInException(
            PayabliErrorCode.SERVER_ERROR,
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
            PayabliErrorCode.DECODING_ERROR,
            DEFAULT_UNDECODABLE_REASON,
            cause = cause?.let(::RedactedCause),
        ) {
        override fun toString(): String = "PayInException.Undecodable"
    }

    public companion object {
        internal const val DEFAULT_REFUSED_REASON: String = "The payment was refused"
        internal const val DEFAULT_SERVICE_ERROR_REASON: String = "The payment could not be processed"
        internal const val DEFAULT_UNDECODABLE_REASON: String = "The response could not be read"
    }
}

/**
 * Carries a cause's type without its message.
 *
 * A decode failure's message quotes the input, which for these bodies can be a card number. `:core` draws the
 * same boundary for the same reason; this is that pattern rather than a new one.
 */
private class RedactedCause(
    cause: Throwable,
) : Throwable("${cause.javaClass.name} (message withheld)")
