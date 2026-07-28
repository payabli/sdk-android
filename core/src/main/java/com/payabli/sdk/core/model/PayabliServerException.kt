package com.payabli.sdk.core.model

/**
 * HTTP 5xx. The body is the same problem-details shape as a 400, without the `errors` map.
 *
 * Classified as [PayabliErrorCode.SERVER_ERROR] rather than a catch-all, because the retry policy needs
 * to tell a server fault apart from an unclassifiable one.
 */
public class PayabliServerException(
    public val httpStatus: Int,
    reason: String = DEFAULT_REASON,
    detail: String? = null,
    public val type: String? = null,
    public val instance: String? = null,
    public val rawCode: String? = null,
    override val retryAfterMillis: Long? = null,
) : PayabliException(PayabliErrorCode.SERVER_ERROR, reason, detail),
    PayabliRetryAfter {
    public companion object {
        public const val DEFAULT_REASON: String = "Internal server error"
    }
}
