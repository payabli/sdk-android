package com.payabli.sdk.taptopay.enrollment

/**
 * Why an activation did not complete. The subtype is the classification, built only by [DeviceActivationFailures];
 * [resultCode] is a diagnostic most refusals share. [reason] is the refusal's own wording: never shown and never
 * logged, because it can quote what was sent.
 */
internal sealed class DeviceActivationException(
    message: String,
    /** The envelope's result code, or null when the failure never reached the service. */
    val resultCode: Int?,
    val reason: String,
    cause: Throwable? = null,
    val httpStatus: Int? = null,
) : Exception(message, cause) {
    /** Never [reason]. */
    override fun toString(): String = "${javaClass.simpleName}(resultCode=$resultCode)"

    /**
     * The code is not six digits, and nothing was sent.
     *
     * Worth its own case because it is the one refusal that costs nothing: a code that is sent counts against
     * the attempt limit, and a typo caught here has not spent one.
     */
    class CodeMalformed :
        DeviceActivationException(
            "the activation code must be six digits; nothing was sent and no attempt was spent",
            null,
            "",
        )

    /** Wrong code. An attempt is now spent. */
    class CodeIncorrect(
        resultCode: Int?,
        reason: String,
        httpStatus: Int?,
    ) : DeviceActivationException(
            "the activation code is wrong; ask for it again, and an attempt is now spent",
            resultCode,
            reason,
            httpStatus = httpStatus,
        )

    /** The code is no longer valid, and the merchant issues another. */
    class CodeExpired(
        resultCode: Int?,
        reason: String,
        httpStatus: Int?,
    ) : DeviceActivationException(
            "the activation code has expired; the merchant must issue a new one",
            resultCode,
            reason,
            httpStatus = httpStatus,
        )

    /** Too many wrong codes. Nothing else can be tried until the merchant issues a new one. */
    class AttemptsExhausted(
        resultCode: Int?,
        reason: String,
        httpStatus: Int?,
    ) : DeviceActivationException(
            "too many activation attempts failed; the merchant must issue a new code",
            resultCode,
            reason,
            httpStatus = httpStatus,
        )

    /** No code has been issued for this device. */
    class CodeNotIssued(
        resultCode: Int?,
        reason: String,
        httpStatus: Int?,
    ) : DeviceActivationException(
            "no activation code has been issued for this device; the merchant must issue one",
            resultCode,
            reason,
            httpStatus = httpStatus,
        )

    /**
     * The stored code could not be read back.
     *
     * Kept apart from [CodeExpired] even though the remedy is the same, so a fault is never filed as an
     * ordinary expiry.
     */
    class CodeUnreadable(
        resultCode: Int?,
        reason: String,
        httpStatus: Int?,
    ) : DeviceActivationException(
            "the stored activation code could not be read; the merchant must issue a new one",
            resultCode,
            reason,
            httpStatus = httpStatus,
        )

    /**
     * The device is not awaiting activation.
     *
     * Already active, or retired and replaced, and this case does not separate the two. Not reported as
     * success: calling the second one activated would be a lie.
     *
     * The message offers no remedy because there is none to offer yet. The record is kept, so a further
     * [DeviceEnrollment.enroll] is answered from it without a call. Separating the two needs a route that
     * reports device status, which this module does not have.
     */
    class DeviceNotPending(
        resultCode: Int?,
        reason: String,
        httpStatus: Int?,
    ) : DeviceActivationException(
            "this device is not awaiting activation; it may already be active, or it may have been replaced",
            resultCode,
            reason,
            httpStatus = httpStatus,
        )

    /**
     * The proof of possession did not verify.
     *
     * Clock skew past what is accepted, or a key that is not the one bound to this device. The first clears
     * once the clock is right, and the code is still good.
     *
     * The record is kept, so [DeviceEnrollment.enroll] is answered from it and re-attesting takes a
     * [DeviceEnrollment.reset] first. That is the second case, and there is nothing yet that tells the two
     * apart from here.
     */
    class AssertionRejected(
        resultCode: Int?,
        reason: String,
        httpStatus: Int?,
    ) : DeviceActivationException(
            "the device assertion did not verify; check the device clock and send the code again",
            resultCode,
            reason,
            httpStatus = httpStatus,
        )

    /**
     * A required field or header was missing or malformed.
     *
     * An SDK defect, and unreachable while the request and assertion types validate their own fields.
     * Separate from the merchant-facing outcomes so it stays loud.
     */
    class RequestRejected(
        resultCode: Int?,
        reason: String,
        httpStatus: Int?,
    ) : DeviceActivationException(
            "the activation request was malformed; this is an SDK defect, please report it",
            resultCode,
            reason,
            httpStatus = httpStatus,
        )

    /**
     * There is no live attestation for this device and key, so the stored identity is discarded. A live one
     * held under another credential is [CredentialMismatch] instead.
     */
    class AttestationRevoked(
        resultCode: Int?,
        reason: String,
        httpStatus: Int?,
    ) : DeviceActivationException(
            "this device is no longer attested; enroll again to attest it",
            resultCode,
            reason,
            httpStatus = httpStatus,
        )

    /**
     * The entry point this call names cannot be used for it.
     *
     * The remedy is to correct the entry point or the credential it was paired with. **Discards nothing.**
     */
    class EntryPointUnusable(
        resultCode: Int?,
        reason: String,
        httpStatus: Int?,
    ) : DeviceActivationException(
            "the configured entry point cannot be used with this access token; check the entry point in " +
                "PayabliConfig and the token your tokenProvider returns",
            resultCode,
            reason,
            httpStatus = httpStatus,
        )

    /** A live attestation for this device is held under another credential. Discards nothing. */
    class CredentialMismatch(
        resultCode: Int?,
        reason: String,
        httpStatus: Int?,
    ) : DeviceActivationException(
            "this device is attested under another credential",
            resultCode,
            reason,
            httpStatus = httpStatus,
        )

    /** The device this record names does not exist under this paypoint. Discards the stored identity. */
    class DeviceUnknown(
        resultCode: Int?,
        reason: String,
        httpStatus: Int?,
    ) : DeviceActivationException(
            "the service has no record of this device; enroll again",
            resultCode,
            reason,
            httpStatus = httpStatus,
        )

    /** Activation was attempted with no stored identity to activate. Nothing was sent. */
    class NotEnrolled : DeviceActivationException("this device is not enrolled; enroll before activating", null, "")

    /**
     * The stored registration is no longer the one the code was requested under. Nothing was sent, since the
     * service would refuse the code for the registration that replaced it.
     */
    class RegistrationReplaced :
        DeviceActivationException(
            "the registration the activation code was requested under was replaced; initialize again",
            null,
            "",
        )

    /**
     * The refusal carried something this mapper does not recognize.
     *
     * The destination for anything unmatched, and it discards nothing. Classification is built on wording, so
     * the unrecognized outcome has to be the safe one.
     */
    class Unclassified(
        resultCode: Int?,
        reason: String,
        httpStatus: Int?,
    ) : DeviceActivationException(
            "the device service refused the activation",
            resultCode,
            reason,
            httpStatus = httpStatus,
        )
}
