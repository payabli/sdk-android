package com.payabli.sdk.taptopay.enrollment

/**
 * Why an activation did not complete, named for what the caller should do about it.
 *
 * Named `DeviceActivationException`: `ActivationException` would sit one word from `AttestationException`
 * in files that import both.
 *
 * The service reports almost all of these under one result code, telling them apart only by message text, so
 * this taxonomy is built by [DeviceActivationFailures] and that is the only place in the module permitted to
 * read the text. The subtype is the classification; [resultCode] is a diagnostic and is 400 for most of the
 * table, which is exactly why the taxonomy cannot be built on it.
 *
 * [reason] is the service's own wording. Displayable, and **never logged** — some of these messages echo
 * what was sent, which is the same rule `DeviceServiceException` states for the same field.
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
     * Worth its own case because it is the one refusal that costs nothing: the service counts an attempt
     * against a five-attempt lockout, and a typo caught here has not spent one.
     */
    class CodeMalformed :
        DeviceActivationException(
            "the activation code must be six digits; nothing was sent and no attempt was spent",
            null,
            "",
        )

    /** Wrong code. One of the five attempts is now spent. */
    class CodeIncorrect(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException(
            "the activation code is wrong; ask for it again, one of five attempts is now spent",
            resultCode,
            reason,
        )

    /** The window closed. The service has cleared the code; the merchant issues another. */
    class CodeExpired(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException(
            "the activation code has expired; the merchant must issue a new one",
            resultCode,
            reason,
        )

    /**
     * Five wrong codes. The service has cleared the code and reset its own counter on the way out, so a
     * fresh code restores the full five.
     */
    class AttemptsExhausted(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException(
            "five activation attempts failed; the merchant must issue a new code, which restores all five",
            resultCode,
            reason,
        )

    /** No code was ever issued for this device. Nobody has minted one yet. */
    class CodeNotIssued(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException(
            "no activation code has been issued for this device; the merchant must issue one",
            resultCode,
            reason,
        )

    /**
     * The service could not read back the code it stored.
     *
     * Kept apart from [CodeExpired] even though the remedy is the same, so a fault on the service's side is
     * never filed as an ordinary expiry.
     */
    class CodeUnreadable(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException(
            "the service could not read the activation code it stored; the merchant must issue a new one",
            resultCode,
            reason,
        )

    /**
     * The device is not awaiting activation.
     *
     * Already active, or retired and replaced. Not reported as success: the service's wording covers both
     * and calling the second one activated would be a lie.
     *
     * The message offers no remedy because there is none to offer yet. The record is kept, so a further
     * [DeviceEnrollment.enroll] is answered from it without asking the service. Telling the two apart needs
     * a route that reports device status, which this module does not have.
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
     * Clock skew past the service's window, or a key the service does not have bound to this device. The
     * first clears once the clock is right, and the code is still good.
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
     * The service holds no live attestation for this device and key.
     *
     * **The one outcome that discards the stored identity.** Also fires when the credential rotated between
     * attesting and activating, because the service's row is keyed on the bearer as well; the remedy is the
     * same either way.
     */
    class AttestationRevoked(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException("this device is no longer attested; enroll again to attest it", resultCode, reason)

    /**
     * The credential is not authorised for this paypoint.
     *
     * Arrives under the same result code as [AttestationRevoked] and means something entirely different: the
     * enrolment is fine and the token is not. **Discards nothing.**
     */
    class EntryNotAuthorized(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException(
            "this access token is not authorized for this paypoint; check its permissions and entry point",
            resultCode,
            reason,
        )

    /** No such paypoint. The host's configuration names one the service does not have. */
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
     * Whether the attempt was counted is not knowable from here: the failure can land either side of the
     * comparison. Sending the code again is what there is to do, and it can be one of the five.
     */
    class ServiceFailed(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException(
            "the device service failed; send the code again, which may cost one of the five attempts",
            resultCode,
            reason,
        )

    /** Activation was attempted with no stored identity to activate. Nothing was sent. */
    class NotEnrolled : DeviceActivationException("this device is not enrolled; enroll before activating", null, "")

    /**
     * The service refused with something this mapper does not recognise.
     *
     * The destination for anything unmatched, and it discards nothing. Classification matches the
     * service's wording, so the unrecognised outcome is the safe one.
     */
    class Unclassified(
        resultCode: Int?,
        reason: String,
    ) : DeviceActivationException("the device service refused the activation", resultCode, reason)
}
