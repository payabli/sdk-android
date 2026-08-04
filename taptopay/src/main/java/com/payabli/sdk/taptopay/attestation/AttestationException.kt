package com.payabli.sdk.taptopay.attestation

import androidx.annotation.RestrictTo

/**
 * Failures from [AppAttestor].
 *
 * Attestation-local rather than new `PayabliErrorCode` cases, for the same reason secure storage keeps
 * its own: the shared error vocabulary is matched string for string by the sibling SDK, and this platform's
 * attestation failures have no counterpart there. "The Play Store is missing" is not a concept the other
 * platform can ever raise, and importing it into a shared taxonomy would oblige that platform to carry a
 * constant it can never return.
 *
 * **The subtypes are dispositions, not error codes.** Play Integrity has two error enums, more than thirty
 * constants between them, and near-total overlap in what a caller can actually *do* about any of them. The
 * four cases below are that set of actions. [errorCode] is kept alongside so the specific constant survives
 * into a log or a report; it is a diagnostic, and branching on it is a sign the disposition is wrong.
 *
 * No message here carries a challenge or a token.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public sealed class AttestationException(
    message: String,
    /**
     * The platform's own error constant, or null where the failure never reached the platform.
     *
     * Safe to log: it is a small negative integer from a published table, and `errorCode` is already an
     * allowlisted log field. It is **not** safe to switch on, because the same integer means different
     * things across the two verdict classes.
     */
    public val errorCode: Int?,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Never includes anything beyond the classification and the code. */
    override fun toString(): String = "${javaClass.simpleName}(errorCode=$errorCode)"

    /**
     * A transient condition on this device. Try again later, with backoff.
     *
     * Network trouble and Google-side unavailability. The platform's guidance is three attempts spaced by
     * roughly five, ten and twenty seconds, and to treat continued failure as a failed integrity check
     * rather than as an outage to wait out.
     *
     * Throttling is deliberately **not** here; see [Throttled].
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class Retryable(
        errorCode: Int?,
        cause: Throwable? = null,
    ) : AttestationException("the integrity request failed transiently and may be retried", errorCode, cause)

    /**
     * The request budget is spent. **Do not retry here.**
     *
     * This is the one failure whose cause is not the device reporting it. The daily request budget belongs
     * to the cloud project and is shared by every app embedding this SDK, so one caller's traffic exhausts
     * it for all of them while each device sees only its own request refused. A device that retries cannot
     * restore a budget it does not own, and many devices retrying together is what turns a throttle into an
     * outage.
     *
     * The platform reports short-term throttling and daily exhaustion with the same code, documented as
     * "has been throttled, or your app has exceeded its daily request quota", and a client cannot tell
     * them apart. That ambiguity is why this is a distinct case rather than a slower [Retryable]: the
     * platform's own rule is to retry transient conditions and not to retry conditions that are not
     * transient, and one of these two branches is each. Only whatever hands out challenges can see which,
     * because only it can see the budget across tenants. A caller that reaches this stops.
     *
     * Card-present callers halt the flow. There is no counterpart on the sibling platform, whose
     * attestation service imposes no vendor-side budget, so this case is asymmetric by construction rather
     * than by omission.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class Throttled(
        errorCode: Int,
        cause: Throwable? = null,
    ) : AttestationException(
            "the shared integrity request budget is spent; this device retrying cannot restore it",
            errorCode,
            cause,
        )

    /**
     * The device cannot answer until something on it changes.
     *
     * A missing, outdated or signed-out Play Store, or Play services in the same condition. Retrying
     * unchanged produces the same answer forever, so this is the case that has to reach a human: either a
     * remediation prompt or a message. It is **not** evidence of tampering.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class RemediationRequired(
        errorCode: Int,
        cause: Throwable? = null,
    ) : AttestationException(
            "the device needs Play Store or Play services attention before it can attest",
            errorCode,
            cause,
        )

    /**
     * Treat as a failed integrity check.
     *
     * The platform marks these non-actionable: the calling app is not installed as the platform
     * understands it, or its UID does not match. Both are what an attack looks like from here, and neither
     * has a remedy to offer. Do not retry and do not degrade to trusting the caller.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class IntegrityFailed(
        errorCode: Int,
        cause: Throwable? = null,
    ) : AttestationException("the integrity check failed and the caller must be treated as untrusted", errorCode, cause)

    /**
     * Our bug, not the device's.
     *
     * A cloud project number that is absent or not one where the API is enabled, or a challenge the
     * platform rejected on shape. The shape half should be unreachable, since [AttestationChallenge]
     * rejects a malformed value at construction; if it arrives anyway, the two validations disagree and
     * that is worth knowing rather than retrying.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class Misconfigured(
        errorCode: Int?,
        cause: Throwable? = null,
    ) : AttestationException("the integrity request was configured wrongly by this SDK", errorCode, cause)

    /**
     * The challenge had already been used.
     *
     * A challenge is single-use by definition, and reusing one asks the platform to mint a second token
     * over a value a verifier has already retired. The verifier would reject the result, so the request is
     * refused here instead: the caller needs a new challenge, not another attempt at this one.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class ChallengeReused :
        AttestationException("this challenge has already been attested; obtain a new one", null)
}
