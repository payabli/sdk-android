package com.payabli.sdk.core.model

/**
 * HTTP 400: an `application/problem+json` document (RFC 9457) plus Payabli's own `code` and `errors`.
 *
 * [httpStatus] comes from the response rather than from the body's own `status` field, because the
 * response is the authority. The body's `token` field is deliberately not surfaced: it is a temporary
 * page identifier, and not decoding it is what keeps it off a `Throwable` that may reach a crash
 * reporter.
 */
public class PayabliValidationException(
    public val httpStatus: Int,
    reason: String = DEFAULT_REASON,
    detail: String? = null,
    /** The error `type`, a documentation URI. */
    public val type: String? = null,
    /** The error `instance`, the request path. A resolved path, so never logged. */
    public val instance: String? = null,
    /** Payabli's own wire code, for example `E1001`. Absent when the body carried none. */
    public val rawCode: String? = null,
    /** Parameter name to its failures. Empty when the body carried none or used an unexpected shape. */
    public val fieldErrors: Map<String, List<PayabliFieldError>> = emptyMap(),
) : PayabliException(PayabliErrorCode.VALIDATION_ERROR, reason, detail) {
    public companion object {
        public const val DEFAULT_REASON: String = "Validation failed"
    }
}
