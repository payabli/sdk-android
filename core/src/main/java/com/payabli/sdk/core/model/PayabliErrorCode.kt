package com.payabli.sdk.core.model

import androidx.annotation.RestrictTo

/**
 * The cross-platform error vocabulary. [wireName] is what telemetry and support tooling match on, and
 * the sibling SDK holds the same strings: never change one without the sibling changing with it.
 *
 * Constants are named identically to their [wireName]. The explicit property is kept anyway, following
 * `HttpMethod.wireName`: `Enum.name` under R8 depends on enum-name retention, and a wire contract
 * should not depend on that.
 */
public enum class PayabliErrorCode(
    /** The identifier as it appears in telemetry and in the sibling SDK. */
    public val wireName: String,
) {
    MISSING_TOKEN("MISSING_TOKEN"),
    TOKEN_EXPIRED("TOKEN_EXPIRED"),
    TOKEN_MALFORMED("TOKEN_MALFORMED"),
    INVALID_SIGNATURE("INVALID_SIGNATURE"),
    PERMISSION_DENIED("PERMISSION_DENIED"),
    SESSION_BURNED("SESSION_BURNED"),

    /**
     * HTTP 402, an issuer decline.
     *
     * Distinguishing this from [UNKNOWN] is what lets the retry policy say "never retry a decline"
     * without matching on prose.
     */
    PAYMENT_DECLINED("PAYMENT_DECLINED"),

    /** HTTP 5xx. Retryable, which is why it is not folded into [UNKNOWN]. */
    SERVER_ERROR("SERVER_ERROR"),

    /**
     * HTTP 429. Retryable, and the only status whose correct handling is unreachable without its own code:
     * folded into [UNKNOWN] it would be un-retryable, because an unclassified status must never be retried.
     */
    RATE_LIMITED("RATE_LIMITED"),

    // Client-side, never returned by the API.
    INVALID_CONFIGURATION("INVALID_CONFIGURATION"),
    NETWORK_ERROR("NETWORK_ERROR"),
    DECODING_ERROR("DECODING_ERROR"),
    USER_CANCELLED("USER_CANCELLED"),
    VALIDATION_ERROR("VALIDATION_ERROR"),
    UNKNOWN("UNKNOWN"),
}

/**
 * Whether this failure leaves it unknown whether the request was carried out.
 *
 * The question is not how bad the failure was but whether the request may have been carried out, because
 * that is what decides between resending the same attempt and making a new one. A money-moving request
 * keeps its idempotency key while this is true and takes a fresh one once it is false.
 *
 * Unknown, so the attempt is kept: a cancellation and a network failure can both land after the bytes were
 * written; a 5xx can follow work already done; a body that would not decode came from a service that
 * answered; and an unexpected error is unexamined by definition.
 *
 * Known, so it is not: a decline and a validation refusal are answers, a rate limit is a refusal to act,
 * and a rejected credential never reached the operation. Keeping an attempt across any of those would
 * claim a repeat that the next request is not.
 *
 * Here rather than in a capability module because both card-not-present and card-present decide this, and
 * the two answering differently is a difference nothing would report.
 *
 * **Restricted, unlike [PayabliErrorCode] itself.** The vocabulary is a host's to catch; which member keeps
 * an attempt alive is this SDK's own retry policy, and publishing it would commit a consumer to a rule that
 * exists to be changed as the services do.
 */
@get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public val PayabliErrorCode.leavesOutcomeUnknown: Boolean
    get() =
        when (this) {
            PayabliErrorCode.USER_CANCELLED,
            PayabliErrorCode.NETWORK_ERROR,
            PayabliErrorCode.SERVER_ERROR,
            PayabliErrorCode.DECODING_ERROR,
            PayabliErrorCode.UNKNOWN,
            -> true

            else -> false
        }
