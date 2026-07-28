package com.payabli.sdk.core.model

/**
 * Root of every error the SDK raises. Genuinely public, and deliberately not `@RestrictTo`: a host app
 * catches this type.
 *
 * Named `PayabliException` rather than `PayabliError` because on the JVM `java.lang.Error` means an
 * unrecoverable condition no application should catch, which is the opposite of what a decline is.
 *
 * **Open, not sealed, on purpose.** Kotlin confines a sealed type's subtypes to one module and one
 * package, which would forbid a capability module from adding its own cases. Exhaustiveness for callers
 * comes from `when (e.code)` over [PayabliErrorCode], which the compiler checks and which keeps working
 * across module boundaries.
 *
 * **[reason] and [detail] may be server text and may echo request data.** Never pass either to
 * `LogField.safe` and never interpolate either into a log message. `Throwable.message` is deliberately
 * only [PayabliErrorCode.wireName], so a stack trace or a crash report carries the classification
 * rather than the prose. Log `LogField.safe("errorCode", code)` instead; `errorCode` is allowlisted.
 */
public abstract class PayabliException protected constructor(
    /** Machine-readable classification. Switch on this, not on the concrete subclass. */
    public val code: PayabliErrorCode,
    /** Short human-readable summary. Displayable; never loggable. */
    public val reason: String,
    /** Longer explanation when the server or the SDK has one. Displayable; never loggable. */
    public val detail: String? = null,
    cause: Throwable? = null,
) : Exception(code.wireName, cause) {
    /** Never includes [reason] or [detail]: either may echo request data. */
    override fun toString(): String = "${javaClass.simpleName}(code=${code.wireName})"
}
