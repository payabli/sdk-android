package com.payabli.sdk.core.logging.impl

/**
 * Field names whose values may be emitted verbatim. Everything else is redacted.
 *
 * Deny-by-default is the point: a field name absent from this set is redacted, so a new sensitive
 * field added anywhere in the SDK is safe on the day it is written, with no change here. Widening
 * this set is a reviewed change to this file, and the reason belongs in the review.
 *
 * Seeded from the session and correlation identifiers that grant nothing on their own, plus transport
 * metadata that carries no subject.
 *
 * Deliberate omissions, each of which deny-by-default gets for free: `path`, `url` and `query` (a
 * resolved path can carry an identifier or a query token, so call sites log the route *template*);
 * `sub` (a subject identifier in most contexts); `transactionRef` (add it where a call site needs it);
 * and `latitude` / `longitude`, which are location data and never loggable.
 *
 * Names are stored already normalised: see [LogFieldRenderer.normalize].
 */
internal object LoggableFieldNames {
    internal val ALLOWED: Set<String> =
        setOf(
            // Session and correlation handles: identifiers that grant nothing alone.
            "sid",
            "jti",
            "kid",
            "familyid",
            "rotationindex",
            "verdict",
            "deviceintegrityverdict",
            "appaccessriskverdict",
            "applicensingverdict",
            "outcome",
            // Non-secret claim vocabulary.
            "aal",
            "scope",
            // Lifecycle and state.
            "event",
            "phase",
            "state",
            "category",
            // Transport metadata. `route` is the route template, never a resolved path.
            "route",
            "method",
            "httpmethod",
            "statuscode",
            "attempt",
            "maxattempts",
            "retryafter",
            "retryable",
            "durationms",
            "elapsedms",
            "timeoutms",
            "contentlength",
            "errorcode",
            "errorkind",
            "sdkversion",
        )
}
