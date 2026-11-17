package com.payabli.sdk.core.model

/**
 * A status of 500 or above. The body is the same problem-details shape as a 400, without the `errors` map.
 */
public class PayabliServerException(
    override val httpStatus: Int,
    reason: String = DEFAULT_REASON,
    detail: String? = null,
    override val rawCode: String? = null,
    override val retryAfterMillis: Long? = null,
) : PayabliException(PayabliErrorType.SERVER_ERROR, reason, detail),
    PayabliRetryAfter {
    public companion object {
        public const val DEFAULT_REASON: String = "Internal server error"
    }
}
