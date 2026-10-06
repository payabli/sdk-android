package com.payabli.sdk.core.model

import androidx.annotation.RestrictTo

/**
 * The SDK's error catalog, one member per cause.
 *
 * Each member carries four values, and none of them is ever changed once published:
 * - [code] is the catalog number: what telemetry reports as `error_number` and what the support procedure is
 *   keyed by. Numbers are allocated in ranges per area, core in the 1000s, card-not-present in the 2000s and
 *   card-present in the 3000s, and are appended and never reused.
 * - [wireName] is the member's identity in telemetry and support tooling. It is explicit rather than read from
 *   `Enum.name` because a wire contract should not depend on R8's enum-name retention.
 * - [category] is the remedy. A host chooses what to do by switching on it; two causes a host repairs the same
 *   way are two members in one category.
 * - [message] is fixed SDK text, safe to display and to log. What the service said is never here: it stays in
 *   [PayabliException.reason] and [PayabliException.detail].
 */
public enum class PayabliErrorType(
    public val code: Int,
    public val wireName: String,
    public val category: PayabliErrorCategory,
    public val message: String,
) {
    MISSING_TOKEN(1001, "MISSING_TOKEN", PayabliErrorCategory.CREDENTIAL, "No access token is available."),
    TOKEN_EXPIRED(1002, "TOKEN_EXPIRED", PayabliErrorCategory.CREDENTIAL, "The access token expired or was rejected."),

    /**
     * Nothing in this SDK raises this, on either platform. Published rather than removed: a host may still
     * be matching on it, and retiring a public member is a larger, separate decision from this one.
     */
    TOKEN_MALFORMED(1003, "TOKEN_MALFORMED", PayabliErrorCategory.CREDENTIAL, "The access token could not be read."),

    /**
     * The host's `tokenProvider` returned no token the SDK could use. [PayabliException.reason] names
     * the specific failure. The SDK does not retry on this code; a subsequent SDK call invokes the
     * callback again.
     */
    TOKEN_PROVIDER_FAILED(
        1004,
        "TOKEN_PROVIDER_FAILED",
        PayabliErrorCategory.CREDENTIAL,
        "The token provider did not return a usable token.",
    ),
    INVALID_SIGNATURE(
        1005,
        "INVALID_SIGNATURE",
        PayabliErrorCategory.CREDENTIAL,
        "The request signature was rejected.",
    ),
    PERMISSION_DENIED(
        1006,
        "PERMISSION_DENIED",
        PayabliErrorCategory.CONFIGURATION,
        "The credentials are not permitted to make this request.",
    ),
    SESSION_BURNED(1007, "SESSION_BURNED", PayabliErrorCategory.CREDENTIAL, "The session can no longer be used."),

    /**
     * HTTP 402, an issuer decline.
     *
     * Distinguishing this from [UNKNOWN] is what lets the retry policy say "never retry a decline"
     * without matching on prose.
     */
    PAYMENT_DECLINED(1008, "PAYMENT_DECLINED", PayabliErrorCategory.DECLINED, "The payment was declined."),

    /** HTTP 5xx. Retryable, which is why it is not folded into [UNKNOWN]. */
    SERVER_ERROR(
        1009,
        "SERVER_ERROR",
        PayabliErrorCategory.OUTCOME_UNKNOWN,
        "The service could not process the request.",
    ),

    /**
     * HTTP 429. Retryable, and the only status whose correct handling is unreachable without its own code:
     * folded into [UNKNOWN] it would be un-retryable, because an unclassified status must never be retried.
     */
    RATE_LIMITED(1010, "RATE_LIMITED", PayabliErrorCategory.RETRY_LATER, "Too many requests. Try again later."),

    /**
     * HTTP 409. The request was not carried out, because the service already holds one like it. That
     * answers the request: sending the same one again is refused again.
     *
     * Whether it answers what the request was *for* is the capability's to say. A repeat sent on a
     * caller's behalf is refused without resolving the attempt it repeats, and the capability reports that
     * on its own result.
     *
     * Folded into [UNKNOWN] it would read as unresolved, and a caller would keep resending a request that
     * cannot succeed.
     */
    CONFLICT(
        1011,
        "CONFLICT",
        PayabliErrorCategory.OUTCOME_UNKNOWN,
        "The request conflicts with the state the service holds.",
    ),

    // Client-side, never returned by the API.
    INVALID_CONFIGURATION(
        1012,
        "INVALID_CONFIGURATION",
        PayabliErrorCategory.CONFIGURATION,
        "The SDK is not configured correctly.",
    ),
    NETWORK_ERROR(1013, "NETWORK_ERROR", PayabliErrorCategory.OUTCOME_UNKNOWN, "The service could not be reached."),
    DECODING_ERROR(1014, "DECODING_ERROR", PayabliErrorCategory.OUTCOME_UNKNOWN, "The response could not be read."),
    USER_CANCELLED(1015, "USER_CANCELLED", PayabliErrorCategory.OUTCOME_UNKNOWN, "The person cancelled."),
    VALIDATION_ERROR(
        1016,
        "VALIDATION_ERROR",
        PayabliErrorCategory.INVALID_REQUEST,
        "The request was refused as invalid.",
    ),
    UNKNOWN(1017, "UNKNOWN", PayabliErrorCategory.OUTCOME_UNKNOWN, "An unexpected error occurred."),

    /** A defect in this SDK, raised before anything was sent, so nothing can have been carried out. */
    SDK_INTERNAL_ERROR(
        1018,
        "SDK_INTERNAL_ERROR",
        PayabliErrorCategory.INTERNAL,
        "The SDK failed before the request was sent.",
    ),
    SESSION_NOT_INITIALIZED(
        1019,
        "SESSION_NOT_INITIALIZED",
        PayabliErrorCategory.INVALID_REQUEST,
        "The session has not been initialized.",
    ),

    // Card-present.
    DEVICE_KEY_UNAVAILABLE(
        3001,
        "DEVICE_KEY_UNAVAILABLE",
        PayabliErrorCategory.RETRY_LATER,
        "This device's secure storage is unavailable.",
    ),
    DEVICE_SETUP_UNSUPPORTED(
        3002,
        "DEVICE_SETUP_UNSUPPORTED",
        PayabliErrorCategory.DEVICE,
        "This device cannot be set up for card-present payments.",
    ),
    DEVICE_SERVICES_OUTDATED(
        3003,
        "DEVICE_SERVICES_OUTDATED",
        PayabliErrorCategory.CONFIGURATION,
        "Google Play on this device must be installed, updated or signed in.",
    ),
    DEVICE_PENDING_ACTIVATION(
        3004,
        "DEVICE_PENDING_ACTIVATION",
        PayabliErrorCategory.CONFIGURATION,
        "This device is waiting for its activation code.",
    ),
    DEVICE_SETUP_REQUIRED(
        3005,
        "DEVICE_SETUP_REQUIRED",
        PayabliErrorCategory.CREDENTIAL,
        "This device must be set up again.",
    ),
    DEVICE_SETUP_REFUSED(
        3006,
        "DEVICE_SETUP_REFUSED",
        PayabliErrorCategory.DEVICE,
        "This device was refused during setup.",
    ),
    DEVICE_SETUP_UNAVAILABLE(
        3007,
        "DEVICE_SETUP_UNAVAILABLE",
        PayabliErrorCategory.RETRY_LATER,
        "Device setup is temporarily unavailable.",
    ),
    DEVICE_SETUP_NOT_CONFIGURED(
        3008,
        "DEVICE_SETUP_NOT_CONFIGURED",
        PayabliErrorCategory.CONFIGURATION,
        "Device setup is not configured for this app or environment.",
    ),
    ENTRY_POINT_REFUSED(
        3009,
        "ENTRY_POINT_REFUSED",
        PayabliErrorCategory.CONFIGURATION,
        "The entry point is not available for this request.",
    ),
    READER_CREDENTIALS_UNUSABLE(
        3010,
        "READER_CREDENTIALS_UNUSABLE",
        PayabliErrorCategory.CONFIGURATION,
        "The card reader's configuration is incomplete.",
    ),
    DEVICE_OS_UNSUPPORTED(
        3011,
        "DEVICE_OS_UNSUPPORTED",
        PayabliErrorCategory.DEVICE,
        "This device's operating system version cannot take contactless payments.",
    ),
    DEVICE_HARDWARE_UNSUPPORTED(
        3012,
        "DEVICE_HARDWARE_UNSUPPORTED",
        PayabliErrorCategory.DEVICE,
        "This device cannot take contactless payments.",
    ),
    TERMS_NOT_ACCEPTED(
        3013,
        "TERMS_NOT_ACCEPTED",
        PayabliErrorCategory.CONFIGURATION,
        "The merchant has not accepted the Tap to Pay terms.",
    ),
    CARD_PRESENT_NOT_ENABLED(
        3014,
        "CARD_PRESENT_NOT_ENABLED",
        PayabliErrorCategory.CONFIGURATION,
        "Card-present payments are not enabled for this paypoint.",
    ),
    READER_DEVICE_REFUSED(
        3015,
        "READER_DEVICE_REFUSED",
        PayabliErrorCategory.DEVICE,
        "The card reader refused this device.",
    ),
    READER_UNAVAILABLE(
        3016,
        "READER_UNAVAILABLE",
        PayabliErrorCategory.RETRY_LATER,
        "The card reader could not be started.",
    ),
    READER_SESSION_EXPIRED(
        3017,
        "READER_SESSION_EXPIRED",
        PayabliErrorCategory.RETRY_LATER,
        "The card reader session expired.",
    ),
    TAP_NOT_COMPLETED(
        3018,
        "TAP_NOT_COMPLETED",
        PayabliErrorCategory.OUTCOME_UNKNOWN,
        "The card read did not complete.",
    ),
    PAYMENT_NOT_OPENED(
        3019,
        "PAYMENT_NOT_OPENED",
        PayabliErrorCategory.DECLINED,
        "The service did not open the payment.",
    ),
    CARD_DECLINED(
        3020,
        "CARD_DECLINED",
        PayabliErrorCategory.DECLINED,
        "The card was declined.",
    ),
    PAYMENT_OUTCOME_UNKNOWN(
        3021,
        "PAYMENT_OUTCOME_UNKNOWN",
        PayabliErrorCategory.OUTCOME_UNKNOWN,
        "The payment's outcome could not be confirmed.",
    ),
    PAYMENT_NOT_CLOSED(
        3022,
        "PAYMENT_NOT_CLOSED",
        PayabliErrorCategory.OUTCOME_UNKNOWN,
        "The payment could not be closed.",
    ),
    ACTIVATION_CODE_MALFORMED(
        3023,
        "ACTIVATION_CODE_MALFORMED",
        PayabliErrorCategory.INVALID_REQUEST,
        "The activation code must be six digits.",
    ),
    ACTIVATION_CODE_INCORRECT(
        3024,
        "ACTIVATION_CODE_INCORRECT",
        PayabliErrorCategory.INVALID_REQUEST,
        "The activation code is incorrect.",
    ),
    ACTIVATION_CODE_EXPIRED(
        3025,
        "ACTIVATION_CODE_EXPIRED",
        PayabliErrorCategory.CONFIGURATION,
        "The activation code has expired.",
    ),
    ACTIVATION_ATTEMPTS_EXHAUSTED(
        3026,
        "ACTIVATION_ATTEMPTS_EXHAUSTED",
        PayabliErrorCategory.CONFIGURATION,
        "Too many incorrect activation codes were entered.",
    ),
    ACTIVATION_CODE_NOT_ISSUED(
        3027,
        "ACTIVATION_CODE_NOT_ISSUED",
        PayabliErrorCategory.CONFIGURATION,
        "No activation code has been issued for this device.",
    ),
    DEVICE_NOT_PENDING(
        3028,
        "DEVICE_NOT_PENDING",
        PayabliErrorCategory.INVALID_REQUEST,
        "This device is not waiting for activation.",
    ),
    TERMINAL_NOT_READY(
        3029,
        "TERMINAL_NOT_READY",
        PayabliErrorCategory.INVALID_REQUEST,
        "The terminal is not ready for this call.",
    ),
    TOO_MANY_OPEN_CHARGES(
        3030,
        "TOO_MANY_OPEN_CHARGES",
        PayabliErrorCategory.INVALID_REQUEST,
        "Too many charges are waiting to be resolved.",
    ),
    PAYMENT_NOT_HELD(
        3031,
        "PAYMENT_NOT_HELD",
        PayabliErrorCategory.INVALID_REQUEST,
        "No captured payment is held under that identifier.",
    ),

    // 3032 is reserved, and no member takes it.
    DEVICE_IDENTITY_UNAVAILABLE(
        3033,
        "DEVICE_IDENTITY_UNAVAILABLE",
        PayabliErrorCategory.DEVICE,
        "This device cannot be identified.",
    ),
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
 * and a rejected credential or a misbehaving provider never reached the operation. Keeping an attempt
 * across any of those would claim a repeat that the next request is not.
 *
 * **A new member lands here by default, not by the compiler.** The `when` below ends `else -> false`, so
 * nothing fails to compile if a member is left unclassified — only
 * `LeavesOutcomeUnknownTest`'s comparison of its two lists against [PayabliErrorType.entries] catches it,
 * and the default it lands on is "known", which for an unclassified failure is the direction that
 * double-charges.
 *
 * Here rather than in a capability module because both card-not-present and card-present decide this, and
 * the two answering differently is a difference nothing would report.
 *
 * **Restricted, unlike [PayabliErrorType] itself.** The vocabulary is a host's to catch; which member keeps
 * an attempt alive is this SDK's own retry policy, and publishing it would commit a consumer to a rule that
 * exists to be changed as the services do.
 */
@get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public val PayabliErrorType.leavesOutcomeUnknown: Boolean
    get() =
        when (this) {
            PayabliErrorType.USER_CANCELLED,
            PayabliErrorType.NETWORK_ERROR,
            PayabliErrorType.SERVER_ERROR,
            PayabliErrorType.DECODING_ERROR,
            PayabliErrorType.UNKNOWN,
            PayabliErrorType.TAP_NOT_COMPLETED,
            PayabliErrorType.PAYMENT_OUTCOME_UNKNOWN,
            PayabliErrorType.PAYMENT_NOT_CLOSED,
            -> true

            else -> false
        }
