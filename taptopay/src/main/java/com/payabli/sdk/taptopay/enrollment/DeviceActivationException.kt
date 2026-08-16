package com.payabli.sdk.taptopay.enrollment

/**
 * Why an activation did not complete, named for what the caller should do about it.
 *
 * Named `DeviceActivationException`: `ActivationException` would sit one word from `AttestationException`
 * in files that import both.
 *
 * Most of these share one result code, so the taxonomy is built by [DeviceActivationFailures] and that is the
 * only place in the module permitted to read a refusal's text. The subtype is the classification;
 * [resultCode] is a diagnostic and is the same value across most of the table, which is exactly why the
 * taxonomy cannot be built on it.
 *
 * [reason] is the refusal's own wording. Displayable, and **never logged** — it can quote what was sent,
 * which is the same rule `DeviceServiceException` states for the same field.
 */
internal sealed class DeviceActivationException(
    message: String,
    /** The envelope's result code, or null when the failure never reached the service. */
    val resultCode: Int?,
    val reason: String,
    cause: Throwable? = null,
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
    ) : DeviceActivationException(
            "the activation code is wrong; ask for it again, and an attempt is now spent",
            resultCode,
            reason,
        )

    /** The code is no longer valid, and the merchant issues another. */
    class CodeExpired(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException(
            "the activation code has expired; the merchant must issue a new one",
            resultCode,
            reason,
        )

    /** Too many wrong codes. Nothing else can be tried until the merchant issues a new one. */
    class AttemptsExhausted(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException(
            "too many activation attempts failed; the merchant must issue a new code",
            resultCode,
            reason,
        )

    /** No code has been issued for this device. */
    class CodeNotIssued(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException(
            "no activation code has been issued for this device; the merchant must issue one",
            resultCode,
            reason,
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
    ) : DeviceActivationException(
            "the stored activation code could not be read; the merchant must issue a new one",
            resultCode,
            reason,
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
    ) : DeviceActivationException(
            "this device is not awaiting activation; it may already be active, or it may have been replaced",
            resultCode,
            reason,
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
    ) : DeviceActivationException(
            "the device assertion did not verify; check the device clock and send the code again",
            resultCode,
            reason,
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
    ) : DeviceActivationException(
            "the activation request was malformed; this is an SDK defect, please report it",
            resultCode,
            reason,
        )

    /**
     * There is no live attestation for this device and key.
     *
     * **The one outcome that discards the stored identity.** Also fires when the credential changed between
     * attesting and activating, since an attestation is valid only for the credential that obtained it; the
     * remedy is the same either way.
     */
    class AttestationRevoked(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException("this device is no longer attested; enroll again to attest it", resultCode, reason)

    /**
     * The credential is not authorised for this paypoint.
     *
     * The enrolment is fine and the credential is not. **Discards nothing.**
     */
    class EntryNotAuthorized(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException(
            "this access token is not authorized for this paypoint; check its permissions and entry point",
            resultCode,
            reason,
        )

    /** No such paypoint. The host's configuration names one that does not exist. */
    class PaypointUnknown(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException(
            "the configured entry point does not exist; check the entry point in PayabliConfig",
            resultCode,
            reason,
        )

    /** The device this record names does not exist under this paypoint. Discards the stored identity. */
    class DeviceUnknown(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException("the service has no record of this device; enroll again", resultCode, reason)

    /**
     * The service failed internally.
     *
     * Whether the attempt counted is not knowable from here. Sending the code again is what there is to do,
     * and it may cost an attempt.
     */
    class ServiceFailed(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException(
            "the device service failed; send the code again, which may cost an attempt",
            resultCode,
            reason,
        )

    /** Activation was attempted with no stored identity to activate. Nothing was sent. */
    class NotEnrolled : DeviceActivationException("this device is not enrolled; enroll before activating", null, "")

    /**
     * The refusal carried something this mapper does not recognise.
     *
     * The destination for anything unmatched, and it discards nothing. Classification is built on wording, so
     * the unrecognised outcome has to be the safe one.
     */
    class Unclassified(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException("the device service refused the activation", resultCode, reason)
}
