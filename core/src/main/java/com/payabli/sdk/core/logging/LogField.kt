package com.payabli.sdk.core.logging

import androidx.annotation.RestrictTo

/**
 * A named value attached to a log record, carrying its own emission disposition.
 *
 * Construct only through [LogField.safe], [LogField.redacted] or [LogField.lastFour]. The subtypes
 * are nested and internal, and Kotlin requires a sealed type's subtypes to sit in the same module
 * and package, so no module outside `:core` can widen this taxonomy. It is a sealed *class* rather
 * than a sealed interface because an interface cannot hold `internal` nested classes.
 *
 * [safe] is not a promise the caller can make unilaterally. The field *name* is checked against
 * `:core`'s loggable allowlist at emission time; an unlisted name is redacted regardless of what
 * the caller believed. Adding a loggable field name is therefore a reviewed change to one file in
 * `:core`, not a decision taken at a call site.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public sealed class LogField {
    /** The field name as it appears in the emitted record. Never redacted; only values are. */
    public abstract val name: String

    /** A value that may be emitted verbatim if [name] survives the allowlist check. */
    internal class Safe(
        override val name: String,
        val rendered: String,
    ) : LogField()

    /** A value that is never emitted. [wasNull] distinguishes `[null]` from `[REDACTED]`. */
    internal class Redacted(
        override val name: String,
        val wasNull: Boolean,
    ) : LogField()

    /** Holds only the trailing four characters, so the full value never reaches this object. */
    internal class LastFour(
        override val name: String,
        val tail: String?,
    ) : LogField()

    public companion object {
        private const val TAIL_LENGTH = 4

        /**
         * Emit verbatim **if** [name] is on the loggable allowlist;
         * otherwise emit `[REDACTED]`. The value is additionally passed through the free-text
         * scrubber, so a mislabelled secret is still caught.
         *
         * The overloads accept only primitives, enums and `String` on purpose: an `Any?` parameter
         * would let a data class's `toString()` dump every field it holds and defeat the allowlist
         * in one call. There is no `Any?` overload and there must never be one.
         */
        public fun safe(
            name: String,
            value: String?,
        ): LogField = if (value == null) Redacted(name, wasNull = true) else Safe(name, value)

        /** See the [String] overload. */
        public fun safe(
            name: String,
            value: Int,
        ): LogField = Safe(name, value.toString())

        /** See the [String] overload. */
        public fun safe(
            name: String,
            value: Long,
        ): LogField = Safe(name, value.toString())

        /** See the [String] overload. */
        public fun safe(
            name: String,
            value: Boolean,
        ): LogField = Safe(name, value.toString())

        /** See the [String] overload. The enum's declared name is emitted, not its `toString()`. */
        public fun safe(
            name: String,
            value: Enum<*>?,
        ): LogField = if (value == null) Redacted(name, wasNull = true) else Safe(name, value.name)

        /**
         * Always `[REDACTED]`, or `[null]` when [value] is null. Accepts `Any?` because the value
         * is discarded unread: only its nullity is observed.
         */
        public fun redacted(
            name: String,
            value: Any?,
        ): LogField = Redacted(name, wasNull = value == null)

        /**
         * `[REDACTED]` followed by the last four characters of [value].
         *
         * NEVER for a PAN, CVV, expiry, track data, key material, or any token value: for those use
         * [redacted]. A four-character tail of a session or reference identifier is a correlation
         * aid; a four-digit tail of a PAN is cardholder data.
         *
         * A value of four characters or fewer, or null, renders as bare `[REDACTED]`.
         */
        public fun lastFour(
            name: String,
            value: String?,
        ): LogField =
            LastFour(
                name,
                if (value != null && value.length > TAIL_LENGTH) value.takeLast(TAIL_LENGTH) else null,
            )
    }
}
