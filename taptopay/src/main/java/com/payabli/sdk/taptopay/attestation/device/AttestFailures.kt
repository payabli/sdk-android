package com.payabli.sdk.taptopay.attestation.device

import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_NOT_FOUND

/**
 * Turns `/attest`'s refusals into [DeviceServiceException] by exact match on the service's text, and defers anything
 * unmatched to the result code. One prefix: the decode failure ends in a status that varies.
 */
internal object AttestFailures : DeviceFailureMapper {
    override fun map(
        resultCode: Int?,
        reason: String,
    ): Throwable? =
        EntryPointFailures.map(resultCode, reason)
            ?: when (resultCode) {
                HTTP_BAD_REQUEST ->
                    when {
                        reason in VERDICT_REFUSED -> DeviceServiceException.IntegrityRefused(resultCode, reason)
                        reason == CHALLENGE_SPENT || reason.startsWith(DECODE_FAILED_PREFIX) ->
                            DeviceServiceException.VerificationUnavailable(resultCode, reason)
                        else -> null
                    }

                HTTP_FORBIDDEN ->
                    when (reason) {
                        IDENTITY_UNKNOWN -> DeviceServiceException.IntegrityRefused(resultCode, reason)
                        NOT_RECOGNIZED -> DeviceServiceException.AppNotAccepted(resultCode, reason)
                        NOT_USABLE -> DeviceServiceException.RegistrationGone(resultCode, reason)
                        else -> null
                    }

                HTTP_NOT_FOUND ->
                    when (reason) {
                        DEVICE_NOT_FOUND -> DeviceServiceException.RegistrationGone(resultCode, reason)
                        else -> null
                    }

                else -> null
            }

    private const val VERIFICATION_FAILED = "Play Integrity verification failed: "
    private const val DECODE_FAILED_PREFIX = "${VERIFICATION_FAILED}Play Integrity API error: "
    private const val CHALLENGE_SPENT = "Challenge not found or already consumed."
    private const val IDENTITY_UNKNOWN = "Application identity could not be determined."
    private const val NOT_RECOGNIZED = "Application is not recognized by Google Play."
    private const val NOT_USABLE = "Device is not in a state that allows this operation."
    private const val DEVICE_NOT_FOUND = "Device not found."

    private val VERDICT_REFUSED =
        setOf(
            "${VERIFICATION_FAILED}Missing tokenPayloadExternal in response.",
            "${VERIFICATION_FAILED}Missing nonce in token payload.",
            "${VERIFICATION_FAILED}Nonce mismatch.",
            "Sandbox attestations are not accepted in this environment.",
        )
}
