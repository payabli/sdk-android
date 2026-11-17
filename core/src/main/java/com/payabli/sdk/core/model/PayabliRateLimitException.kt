package com.payabli.sdk.core.model

/**
 * HTTP 429: a client quota is exhausted, which is not a server fault, so not a [PayabliServerException].
 * Retryable, and [retryAfterMillis] is the server's own instruction about when. A caller that ignores it
 * and retries on its own schedule is violating the limit the server just declared.
 */
public class PayabliRateLimitException internal constructor(
    override val retryAfterMillis: Long?,
    reason: String,
    detail: String?,
    httpStatus: Int?,
    rawCode: String?,
) : PayabliException(PayabliErrorType.RATE_LIMITED, reason, detail, null, httpStatus, rawCode),
    PayabliRetryAfter {
    /**
     * A rate limit no response was read for.
     *
     * @param retryAfterMillis the wait asked for, when there is one.
     * @param reason short human-readable summary.
     * @param detail longer explanation, when there is one.
     */
    public constructor(
        retryAfterMillis: Long? = null,
        reason: String = DEFAULT_REASON,
        detail: String? = null,
    ) : this(retryAfterMillis, reason, detail, null, null)

    /** A rate limit with no wait and the default reason. */
    public constructor() : this(null)

    public companion object {
        public const val DEFAULT_REASON: String = "Rate limited (429)"
    }
}
