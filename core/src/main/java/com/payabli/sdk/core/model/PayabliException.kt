package com.payabli.sdk.core.model

/**
 * Root of every error the SDK raises. Public and not `@RestrictTo`, because a host app catches this type.
 *
 * Named `PayabliException` rather than `PayabliError` because on the JVM `java.lang.Error` means an
 * unrecoverable condition no application should catch, which is the opposite of what a decline is.
 *
 * **The catalog entry's fields are read off the error directly.** [code] is the catalog number, [category] the
 * remedy a host switches on, and `message` the catalog's fixed text, which is safe to display and to log. [type]
 * is the entry itself, for a `when` over every cause.
 *
 * **Open, not sealed.** Kotlin confines a sealed type's subtypes to one module and one
 * package, which would forbid a capability module from adding its own cases. Exhaustiveness for callers
 * comes from `when (e.type)` over [PayabliErrorType], which the compiler checks and which keeps working
 * across module boundaries.
 *
 * **[reason] and [detail] may be server text and may echo request data.** Never pass either to
 * `LogField.safe` and never interpolate either into a log message. `Throwable.message` is the catalog's fixed
 * text and never either of them, so a stack trace or a crash report carries the classification rather than the
 * prose. Log `LogField.safe("errorCode", type)` instead; `errorCode` is allowlisted.
 *
 * @property type the catalog entry. Switch on this, or on [category], not on the concrete subclass.
 * @property reason short summary, which can be the service's text. Never shown to a payer or an operator, and never
 *   logged: it can quote what was submitted. Show a message for [category] instead.
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
