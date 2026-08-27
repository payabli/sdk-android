package com.payabli.sdk.core.telemetry

import androidx.annotation.RestrictTo

/**
 * The property keys an event may carry.
 *
 * Narrow by design: a key holds values from a small closed set or a number, and anything needing a wide
 * value space belongs in the event name instead.
 *
 * **An enum so a key cannot be misspelled into existence.** [key] is derived from the constant name, and
 * Kotlin's convention for one is `SCREAMING_SNAKE_CASE`, so lowercasing it is snake_case by construction.
 * The far side groups by key, and it accepts `^[a-z][A-Za-z0-9_]{0,31}$` — both spellings — so a `retryCount`
 * beside a `retry_count` would be two columns for one thing and neither complete.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public enum class TelemetryProperty {
    /** How the operation the event names ended. Values come from [TelemetryProperties.Outcome]. */
    OUTCOME,

    /** A numeric service or platform code, as text. */
    CODE,

    /** Why, from a fixed vocabulary. Never free text and never text a server supplied. */
    REASON,

    /** Elapsed milliseconds for the operation the event names. */
    DURATION_MS,

    /** 1-based attempt number. */
    ATTEMPT,

    /** A lifecycle state name, from the emitting machine's own fixed set. */
    STATE,

    /** The state a transition left. */
    FROM,

    /** The state a transition reached. */
    TO,

    /** A named step within a multi-step flow. */
    STEP,

    /** Which input a form event is about, by name. Never what was typed into one. */
    FIELD,
    ;

    /** The key as it goes on the wire. */
    public val key: String get() = name.lowercase()
}

/**
 * The fixed vocabularies a property value comes from.
 *
 * Values, not keys: [TelemetryProperty] is the key half. Nothing here ever carries an instrument, a payer, a
 * credential, a resolved request path or text supplied by a server. `TelemetryCatalog` enforces the key half
 * of that; the value half is the emitting site's, which is why every value below comes from a constant.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object TelemetryProperties {
    /** The values [OUTCOME] may take. Each family uses the subset its own KDoc names. */
    public object Outcome {
        /** The call answered as asked. Device routes and card-present lifecycle. */
        public const val SUCCEEDED: String = "succeeded"

        /** The payment was declined. Money path and card-present lifecycle. */
        public const val DECLINED: String = "declined"

        /** The request was refused before it could do what it asked. Device routes. */
        public const val REFUSED: String = "refused"

        /** The call did not complete. Device routes and card-present lifecycle. */
        public const val FAILED: String = "failed"

        /** The payment was taken. Money path. */
        public const val APPROVED: String = "approved"

        /** Refused by the SDK without a request being sent. Money path. */
        public const val REFUSED_LOCALLY: String = "refused_locally"

        /** Cancelled, or the caller went away before an outcome arrived. Money path. */
        public const val INTERRUPTED: String = "interrupted"

        /** The outcomes that mean the operation did what it was asked. Everything else forces a send. */
        public val SUCCESSFUL: Set<String> = setOf(SUCCEEDED, APPROVED)
    }
}
