package com.payabli.sdk.core.model

/**
 * Root of every error the SDK raises. Open rather than sealed, so a capability module can add its own subtypes;
 * switch on [type] or [category]. `message` is the catalog's fixed text, safe to show and to log. [reason] and
 * [detail] can be the service's text quoting what was submitted, so log `LogField.safe("errorCode", type)` instead.
 *
 * @property type the catalog entry. Switch on this, or on [category], not on the concrete subclass.
 * @property reason short summary, which can be the service's text. Never shown to a payer and never logged.
 * @property detail longer explanation when the server or the SDK has one. Never shown and never logged, as [reason].
 */
public abstract class PayabliException internal constructor(
    public val type: PayabliErrorType,
    public val reason: String,
    public val detail: String?,
    cause: Throwable?,
    private val responseStatus: Int?,
    private val responseCode: String?,
) : Exception(type.message, cause) {
    /**
     * A failure carrying neither a status nor a code.
     *
     * @param type the catalog entry.
     * @param reason short human-readable summary.
     * @param detail longer explanation, when there is one.
     * @param cause the underlying failure, when there is one.
     */
    protected constructor(
        type: PayabliErrorType,
        reason: String,
        detail: String? = null,
        cause: Throwable? = null,
    ) : this(type, reason, detail, cause, null, null)

    /** The catalog number. */
    public val code: Int get() = type.code

    /** What a host does about this failure. */
    public val category: PayabliErrorCategory get() = type.category

    /**
     * The status line of the response this failure was read from, never a body's own `status` field.
     *
     * Null when no response was read: the request never reached the service, its answer was lost, or this SDK
     * refused the call before sending it.
     */
    public open val httpStatus: Int? get() = responseStatus

    /**
     * The service's own code for this failure, for example `E1001`.
     *
     * Null when no response was read, when the response named no code, or when its body could not be read.
     */
    public open val rawCode: String? get() = responseCode

    /**
     * Never includes [reason] or [detail]: either may echo request data.
     *
     * @return the class name, the code and the type's wire name.
     */
    override fun toString(): String = "${javaClass.simpleName}(code=$code, type=${type.wireName})"
}
