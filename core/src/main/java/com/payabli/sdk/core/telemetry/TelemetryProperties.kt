package com.payabli.sdk.core.telemetry

import androidx.annotation.RestrictTo

/**
 * The property keys an event may carry, and the fixed vocabularies their values come from.
 *
 * Narrow by design: a key here holds values from a small closed set or a number, which is what
 * makes a record readable in aggregate; anything needing a wide value space belongs in the event name
 * instead, and the device routes and the money path are both worked examples of that.
 *
 * Nothing here ever carries an instrument, a payer, a credential, a resolved request path or text supplied by
 * a server. [TelemetryCatalog] enforces the key half of that; the value half is the emitting site's, which is
 * why every value below comes from a constant rather than from a response.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object TelemetryProperties {
    /** How the operation the event names ended. Values come from [Outcome]. */
    public const val OUTCOME: String = "outcome"

    /** A numeric service or platform code, as text. */
    public const val CODE: String = "code"

    /** Why, from a fixed vocabulary. Never free text and never text a server supplied. */
    public const val REASON: String = "reason"

    /** Elapsed milliseconds for the operation the event names. */
    public const val DURATION_MS: String = "duration_ms"

    /** 1-based attempt number. */
    public const val ATTEMPT: String = "attempt"

    /** A lifecycle state name, from the emitting machine's own fixed set. */
    public const val STATE: String = "state"

    /** The state a transition left. */
    public const val FROM: String = "from"

    /** The state a transition reached. */
    public const val TO: String = "to"

    /** A named step within a multi-step flow. */
    public const val STEP: String = "step"

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
        public const val REFUSED_LOCALLY: String = "refusedLocally"

        /** Cancelled, or the caller went away before an outcome arrived. Money path. */
        public const val INTERRUPTED: String = "interrupted"
    }
}
