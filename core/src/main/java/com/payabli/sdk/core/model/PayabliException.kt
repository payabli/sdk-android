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
 */
public abstract class PayabliException protected constructor(
    /** The catalog entry. Switch on this, or on [category], not on the concrete subclass. */
    public val type: PayabliErrorType,
    /** Short human-readable summary. Displayable; never loggable. */
    public val reason: String,
    /** Longer explanation when the server or the SDK has one. Displayable; never loggable. */
    public val detail: String? = null,
    cause: Throwable? = null,
) : Exception(type.message, cause) {
    /** The catalog number. */
    public val code: Int get() = type.code

    /** What a host does about this failure. */
    public val category: PayabliErrorCategory get() = type.category

    /** Never includes [reason] or [detail]: either may echo request data. */
    override fun toString(): String = "${javaClass.simpleName}(code=$code, type=${type.wireName})"
}
