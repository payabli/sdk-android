package com.payabli.sdk.core.model

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
