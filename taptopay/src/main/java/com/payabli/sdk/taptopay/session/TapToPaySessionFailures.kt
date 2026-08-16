package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.taptopay.attestation.AttestationException
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceException
import com.payabli.sdk.taptopay.enrollment.DeviceActivationException
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.ATTESTATION_REQUIRED
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.CONFIGURATION_REJECTED
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.SDK_INTERNAL_ERROR
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.SERVICE_UNAVAILABLE

/**
 * Where a session lands when the work under it fails.
 *
 * One place, so every phase of every entry point ends the same way.
 *
 * **A landing is a remedy.** Two failures a host repairs identically share a member of
 * [TapToPayFailureReason], and a failure whose remedy is unknown is [SDK_INTERNAL_ERROR]: a guess sends a host down a
 * repair that cannot work.
 *
 * **Discarding the device's identity requires a positive match.** Only a refusal that names the attestation
 * lands on [ATTESTATION_REQUIRED]. Everything unrecognised lands where being wrong costs nothing.
 */
internal object TapToPaySessionFailures {
    /**
     * The state to publish for [failure], or null to leave the session where it is.
     *
     * A wrong activation code fails the call and changes nothing about the session: the device still owes a
     * code, which is what the state already says, and moving it takes away the state a host collects under.
     */
    fun landingFor(failure: Throwable): TapToPaySessionState? =
        when (failure) {
            is TapToPaySessionException.PendingActivation -> TapToPaySessionState.PendingActivation
            is TapToPaySessionException.AttestationRequired -> failed(ATTESTATION_REQUIRED)
            is TapToPaySessionException.NotRecoverable -> null
            is TapToPaySessionException.SetupAbandoned -> TapToPaySessionState.Idle
            is TapToPaySessionException.SetupFailed -> failed(SDK_INTERNAL_ERROR)
            is DeviceServiceException -> landingForService(failure)
            is DeviceActivationException -> landingForActivation(failure)
            is AttestationException -> landingForAttestation(failure)
            is DeviceKeyException -> landingForDeviceKey(failure)
            is PayabliException -> landingForTransport(failure)
            else -> failed(SDK_INTERNAL_ERROR)
        }

    /**
     * A device that owes activation and a credential that is not scoped for the call arrive as one case, so
     * both land here: the reader is unavailable either way.
     *
     * Nothing found discards nothing. More than one thing can be the one that was not found, and only one of
     * them means the stored identity is stale, so the safe landing is the one that keeps it.
     */
    private fun landingForService(failure: DeviceServiceException): TapToPaySessionState? =
        when (failure) {
            is DeviceServiceException.Forbidden -> TapToPaySessionState.PendingActivation
            is DeviceServiceException.NotAttested -> failed(ATTESTATION_REQUIRED)
            is DeviceServiceException.NotFound -> failed(CONFIGURATION_REJECTED)
            // The request this SDK built was refused, which makes it this SDK's defect.
            is DeviceServiceException.BadRequest -> failed(SDK_INTERNAL_ERROR)
            is DeviceServiceException.ServerFailure -> failed(SERVICE_UNAVAILABLE)
            is DeviceServiceException.Undecodable -> failed(SDK_INTERNAL_ERROR)
            is DeviceServiceException.Unclassified -> failed(SERVICE_UNAVAILABLE)
        }

    /**
     * Most activation failures leave the session alone, because the device still owes the code the caller
     * was in the middle of spending.
     *
     * Two of them say the record names a device or an attestation that is gone.
     */
    private fun landingForActivation(failure: DeviceActivationException): TapToPaySessionState? =
        when (failure) {
            is DeviceActivationException.AttestationRevoked -> failed(ATTESTATION_REQUIRED)
            is DeviceActivationException.DeviceUnknown -> failed(ATTESTATION_REQUIRED)
            is DeviceActivationException.NotEnrolled -> failed(ATTESTATION_REQUIRED)
            is DeviceActivationException.EntryNotAuthorized -> failed(CONFIGURATION_REJECTED)
            is DeviceActivationException.PaypointUnknown -> failed(CONFIGURATION_REJECTED)
            is DeviceActivationException.ServiceFailed -> failed(SERVICE_UNAVAILABLE)
            else -> null
        }

    /**
     * A platform verdict, which would be refused anyway.
     *
     * The two the platform says to ask again about are service failures. Nothing about the device changed,
     * so a host is told to retry.
     */
    private fun landingForAttestation(failure: AttestationException): TapToPaySessionState? =
        when (failure) {
            is AttestationException.Retryable -> failed(SERVICE_UNAVAILABLE)
            is AttestationException.Throttled -> failed(SERVICE_UNAVAILABLE)
            is AttestationException.Misconfigured -> failed(CONFIGURATION_REJECTED)
            else -> failed(ATTESTATION_REQUIRED)
        }

    /**
     * A key that is gone is the identity being gone, which is a positive match: enrollment discards the
     * record before raising it, so the remedy is to attest again.
     *
     * The other two are not. A signature that failed and a platform that cannot do crypto both leave the
     * key where it was, so neither says the identity is stale.
     */
    private fun landingForDeviceKey(failure: DeviceKeyException): TapToPaySessionState =
        when (failure) {
            is DeviceKeyException.KeyLost -> failed(ATTESTATION_REQUIRED)
            is DeviceKeyException.SigningFailed -> failed(SDK_INTERNAL_ERROR)
            is DeviceKeyException.CryptoUnavailable -> failed(SDK_INTERNAL_ERROR)
        }

    private fun landingForTransport(failure: PayabliException): TapToPaySessionState =
        when (failure.code) {
            PayabliErrorCode.PERMISSION_DENIED -> TapToPaySessionState.PendingActivation
            PayabliErrorCode.INVALID_CONFIGURATION -> failed(CONFIGURATION_REJECTED)
            PayabliErrorCode.DECODING_ERROR -> failed(SDK_INTERNAL_ERROR)
            else -> failed(SERVICE_UNAVAILABLE)
        }

    private fun failed(reason: TapToPayFailureReason): TapToPaySessionState = TapToPaySessionState.Failed(reason)
}
