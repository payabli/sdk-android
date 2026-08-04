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
            // Which of the two integrity request shapes was made: the fixed vocabulary standard / classic.
            // Needed because the same error code means different things in each, so a record naming only
            // the code cannot be read without it.
            "verdictclass",
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
            // Three distinct durations, deliberately not collapsed into one name: `timeoutms` is the
            // backoff wait before the next attempt, `totaltimeoutms` the retry budget, `calltimeoutms`
            // the ceiling on one whole call. An incident reads differently depending on which ran out.
            "timeoutms",
            "totaltimeoutms",
            "calltimeoutms",
            "contentlength",
            "errorcode",
            "errorkind",
            "sdkversion",
            // Storage key diagnostics. `keyalias` is a constant prefix plus a truncated SHA-256 of the
            // store's canonical path, so it names an entry without naming a subject; `securitylevel` is
            // the fixed vocabulary strongbox / tee / software. Both say which key failed and how well it
            // was protected, which is the whole content of those two records.
            "keyalias",
            "securitylevel",
        )
}
