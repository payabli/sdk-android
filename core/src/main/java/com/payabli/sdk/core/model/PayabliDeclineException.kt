package com.payabli.sdk.core.model

/**
 * HTTP 402, an issuer decline. The body is the v2 envelope's scalar half:
 * `{ code, reason, explanation, action }`.
 *
 * Distinct from the legacy envelope's business decline, which arrives as HTTP 200 with
 * `isSuccess: false` on the device routes. Do not conflate them: this type only ever comes from a 402.
 *
 * [rawCode] is nullable so that a proxy-generated 402 with no body is still classified as a decline
 * rather than falling back to an unclassifiable error.
 *
 * @property rawCode the processor decline code, for example `D0329`. Null when the body carried none.
 * @property explanation longer server explanation. Also surfaced as [detail].
 * @property action the remediation the server suggests.
 */
public class PayabliDeclineException internal constructor(
    override val rawCode: String?,
    reason: String,
    public val explanation: String?,
    public val action: String?,
    httpStatus: Int?,
) : PayabliException(PayabliErrorType.PAYMENT_DECLINED, reason, explanation, null, httpStatus, rawCode) {
    /**
     * A decline no response was read for.
     *
     * @param rawCode the processor decline code, when there is one.
     * @param reason short human-readable summary.
     * @param explanation longer server explanation, when there is one.
     * @param action the remediation the server suggests, when there is one.
     */
    public constructor(
        rawCode: String? = null,
        reason: String = DEFAULT_REASON,
        explanation: String? = null,
        action: String? = null,
    ) : this(rawCode, reason, explanation, action, null)

    /** A decline with no code and the default reason. */
    public constructor() : this(null)

    public companion object {
        public const val DEFAULT_REASON: String = "Payment declined (402)"
    }
}
