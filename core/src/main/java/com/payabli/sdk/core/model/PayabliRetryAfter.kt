package com.payabli.sdk.core.model

/**
 * A server-supplied backoff hint, from an RFC 9110 `Retry-After` response header.
 *
 * An interface implemented only by the errors whose status can carry one, rather than a field on every
 * [PayabliException]: a retry hint is not a property of a validation failure. Read it with
 * `(error as? PayabliRetryAfter)?.retryAfterMillis`, which is preferable to a `when` over concrete
 * subclasses for the reason [PayabliException] gives.
 *
 * Milliseconds rather than seconds, because both wire forms resolve to a duration and `delay` takes millis.
 */
public interface PayabliRetryAfter {
    /** How long to wait, resolved from either wire form. Null when the server supplied none. */
    public val retryAfterMillis: Long?
}
