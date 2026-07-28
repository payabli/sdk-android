package com.payabli.sdk.core.model

/**
 * HTTP 429, the server declining because a quota is exhausted.
 *
 * Not folded into [PayabliServerException], whose `httpStatus` and documentation mean 5xx: a 429 is a
 * client-quota condition rather than a server fault, and the retry policy treats the two the same only by
 * coincidence.
 *
 * Retryable, and [retryAfterMillis] is the server's own instruction about when. A caller that ignores it
 * and retries on its own schedule is violating the limit the server just declared.
 */
public class PayabliRateLimitException(
    override val retryAfterMillis: Long? = null,
    reason: String = DEFAULT_REASON,
    detail: String? = null,
) : PayabliException(PayabliErrorCode.RATE_LIMITED, reason, detail),
    PayabliRetryAfter {
    public companion object {
        public const val DEFAULT_REASON: String = "Rate limited (429)"
    }
}
