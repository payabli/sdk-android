package com.payabli.sdk.core.model

import java.util.Collections

/**
 * HTTP 400: an `application/problem+json` document (RFC 9457) plus Payabli's own `code` and `errors`.
 *
 * [httpStatus] comes from the response, not from the body's own `status` field. The body's `token` field
 * is not decoded: it is a temporary page identifier, and this `Throwable` may reach a crash reporter.
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
    /**
     * Parameter name to its failures. Empty when the body carried none or used an unexpected shape.
     *
     * The platform sends each failure as a bare string, which arrives as [PayabliFieldError.message] with
     * a null [PayabliFieldError.suggestion]. A key of `$` is the request body as a whole rather than a
     * field in it, which is how a missing required property reports itself.
     */
    fieldErrors: Map<String, List<PayabliFieldError>> = emptyMap(),
) : PayabliException(PayabliErrorCode.VALIDATION_ERROR, reason, detail) {
    public val fieldErrors: Map<String, List<PayabliFieldError>> = Collections.unmodifiableMap(fieldErrors.toMap())

    public companion object {
        public const val DEFAULT_REASON: String = "Validation failed"
    }
}
