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
 */
public class PayabliDeclineException(
    /** The processor decline code, for example `D0329`. Null when the body carried none. */
    public val rawCode: String? = null,
    reason: String = DEFAULT_REASON,
    /** Longer server explanation. Also surfaced as [detail]. */
    public val explanation: String? = null,
    /** The remediation the server suggests. */
    public val action: String? = null,
) : PayabliException(PayabliErrorCode.PAYMENT_DECLINED, reason, detail = explanation) {
    public companion object {
        public const val DEFAULT_REASON: String = "Payment declined (402)"
    }
}
