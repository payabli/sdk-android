package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.taptopay.attestation.device.DeviceFailureMapper
import com.payabli.sdk.taptopay.attestation.device.EntryPointFailures
import java.net.HttpURLConnection

/**
 * Turns `/activate`'s refusals into [DeviceActivationException], by reading the service's own wording.
 *
 * The only place in this module that reads a decline's text, which is the role the mapper interface was
 * given. Every literal is here, in one file, so the day the service stops distinguishing its failures by
 * text there is one file to rewrite. The entry-point literal is the one exception and lives on
 * [EntryPointFailures], because four other routes compare it too.
 *
 * **A result code alone cannot say whether the stored identity goes.** Some refusals mean the record names
 * something that is gone, and others mean the host configured a credential or an entry point wrong.
 * Discarding on the second would destroy a working enrolment over a token that was simply scoped wrong.
 *
 * So **the destructive classifications require a positive match and everything else falls to
 * [DeviceActivationException.Unclassified]**, which discards nothing. A service that rewords its messages
 * then stops discarding, and never starts discarding the wrong ones.
 *
 * Exact comparison against ASCII literals the service emits, and two prefix matches where it appends detail.
 * Never case-insensitive, never locale-sensitive, never a pattern — each of those would widen a match that
 * is load-bearing precisely because it is narrow.
 *
 * **Pure.** The sibling SDK discards the identity from inside its equivalent of this function; this one
 * classifies and nothing else, so it can be tested for classification alone. [DeviceEnrollment] acts on the
 * result.
 */
internal class DeviceActivationFailures(
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) : DeviceFailureMapper {
    override fun map(
        resultCode: Int?,
        reason: String,
    ): Throwable =
        when {
            resultCode == HttpURLConnection.HTTP_BAD_REQUEST ->
                when {
                    reason == INVALID_CODE -> DeviceActivationException.CodeIncorrect(resultCode, reason)
                    reason == CODE_EXPIRED -> DeviceActivationException.CodeExpired(resultCode, reason)
                    reason == TOO_MANY_ATTEMPTS -> DeviceActivationException.AttemptsExhausted(resultCode, reason)
                    reason == NO_CHALLENGE -> DeviceActivationException.CodeNotIssued(resultCode, reason)
                    reason == STORED_CODE_INVALID -> DeviceActivationException.CodeUnreadable(resultCode, reason)
                    reason == NOT_PENDING -> DeviceActivationException.DeviceNotPending(resultCode, reason)
                    reason.startsWith(ASSERTION_FAILED_PREFIX) ->
                        DeviceActivationException.AssertionRejected(resultCode, reason)
                    reason in MALFORMED_REQUEST -> DeviceActivationException.RequestRejected(resultCode, reason)
                    else -> unclassified(resultCode, reason)
                }

            // The two meanings of 401. Only the first discards anything.
            resultCode == HttpURLConnection.HTTP_UNAUTHORIZED && reason == ATTESTATION_REVOKED ->
                DeviceActivationException.AttestationRevoked(resultCode, reason)
            resultCode == HttpURLConnection.HTTP_UNAUTHORIZED && reason == NOT_AUTHORIZED_FOR_ENTRY ->
                DeviceActivationException.EntryNotAuthorized(resultCode, reason)

            // And of 404.
            resultCode == HttpURLConnection.HTTP_NOT_FOUND && reason == DEVICE_NOT_FOUND ->
                DeviceActivationException.DeviceUnknown(resultCode, reason)
            resultCode == HttpURLConnection.HTTP_NOT_FOUND && reason.startsWith(PAYPOINT_NOT_FOUND_PREFIX) ->
                DeviceActivationException.PaypointUnknown(resultCode, reason)

            // The literal is EntryPointFailures', so this route and the four that use that mapper cannot
            // drift onto two spellings of one comparison.
            resultCode == HttpURLConnection.HTTP_FORBIDDEN && reason == EntryPointFailures.ENTRY_POINT_UNUSABLE ->
                DeviceActivationException.EntryPointUnusable(resultCode, reason)

            resultCode != null && resultCode >= HttpURLConnection.HTTP_INTERNAL_ERROR ->
                DeviceActivationException.ServiceFailed(resultCode, reason)

            else -> unclassified(resultCode, reason)
        }

    /**
     * The safe destination, and a record that it was reached.
     *
     * The log line is the tripwire: this classification is built on wording the service is expected to stop
     * emitting one day, and this is what will say so. The wording itself is never logged.
     */
    private fun unclassified(
        resultCode: Int?,
        reason: String,
    ): DeviceActivationException {
        logger.warn(
            LogField.safe("event", "activation_reason_unmapped"),
            LogField.safe("errorCode", resultCode?.toString() ?: "none"),
        ) { "the device service refused the activation with an unrecognized reason" }
        return DeviceActivationException.Unclassified(resultCode, reason)
    }

    private companion object {
        const val INVALID_CODE = "Invalid activation code."
        const val CODE_EXPIRED = "Activation code has expired. Request a new challenge."
        const val TOO_MANY_ATTEMPTS = "Too many failed activation attempts. Request a new challenge."
        const val NO_CHALLENGE = "No active challenge for this device."
        const val STORED_CODE_INVALID = "Stored activation code is invalid."
        const val NOT_PENDING = "Device is not pending activation."
        const val ASSERTION_FAILED_PREFIX = "Assertion verification failed: "
        const val ATTESTATION_REVOKED = "Device not attested or attestation revoked."
        const val NOT_AUTHORIZED_FOR_ENTRY = "Not authorized for this entry point."
        const val DEVICE_NOT_FOUND = "Device not found."
        const val PAYPOINT_NOT_FOUND_PREFIX = "Paypoint '"

        /** The fixed body and header complaints. Every one is an SDK defect, not a merchant action. */
        val MALFORMED_REQUEST =
            setOf(
                "entry is required in the request body.",
                "deviceId is required in the request body.",
                "activationCode is required in the request body.",
                "X-App-Assertion header is required.",
                "X-App-KeyId header is required.",
                "X-Assertion-Timestamp header is required.",
            )
    }
}
