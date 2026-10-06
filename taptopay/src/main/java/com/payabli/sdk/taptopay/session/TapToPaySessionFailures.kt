package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.attestation.AttestationException
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceException
import com.payabli.sdk.taptopay.enrollment.DeviceActivationException
import com.payabli.sdk.taptopay.enrollment.StoredRegistration
import com.payabli.sdk.taptopay.provider.DeviceIneligibleException
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.CONFIGURATION_REJECTED
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.DEVICE_INELIGIBLE
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.DEVICE_KEY_UNAVAILABLE
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.DEVICE_SETUP_REQUIRED
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
 * lands on [DEVICE_SETUP_REQUIRED]. Everything unrecognised lands where being wrong costs nothing.
 *
 * **Pending activation needs a stored registration.** Its remedy is a code for this device, so it is landed
 * only when a registration holds the id that code is requested under.
 */
internal object TapToPaySessionFailures {
    /**
     * The state to publish for [failure], or null to leave the session where it is. [registration] decides
     * only a failure that says the device owes activation.
     *
     * A wrong activation code fails the call and changes nothing about the session: the device still owes a
     * code, which is what the state already says, and moving it takes away the state a host collects under.
     */
    fun landingFor(
        failure: Throwable,
        registration: StoredRegistration,
    ): TapToPaySessionState? = if (owesActivation(failure)) pendingOn(registration) else landingFor(failure)

    /**
     * The failure a caller is given for [failure], so its code agrees with the state [landingFor] published.
     * A registration that cannot be read raises its storage refusal. With none stored, the original is the
     * cause, which keeps any text the service sent.
     */
    fun raisedFor(
        failure: Exception,
        registration: StoredRegistration,
    ): Exception =
        when {
            !owesActivation(failure) -> failure
            registration is StoredRegistration.Held -> failure
            registration is StoredRegistration.Unreadable -> registration.refusal
            // Already the code the configuration landing is reported under.
            failure is PayabliException -> failure
            else -> TapToPaySessionException.NotPermitted(failure)
        }

    /**
     * The failures that say the device owes activation. A device that owes it and an application this
     * paypoint does not permit arrive as one refusal, so both are here, and [registration] tells them apart.
     */
    private fun owesActivation(failure: Throwable): Boolean =
        failure is TapToPaySessionException.PendingActivation ||
            failure is DeviceServiceException.Forbidden ||
            (failure is PayabliException && failure.type == PayabliErrorType.PERMISSION_DENIED)

    /** A registration that cannot be read lands where its storage refusal does. */
    private fun pendingOn(registration: StoredRegistration): TapToPaySessionState =
        when (registration) {
            is StoredRegistration.Held -> TapToPaySessionState.PendingActivation(registration.activationId)
            StoredRegistration.None -> failed(CONFIGURATION_REJECTED)
            is StoredRegistration.Unreadable -> landingForStorage(registration.refusal)
        }

    private fun landingFor(failure: Throwable): TapToPaySessionState? =
        when (failure) {
            // Decided by the registration, before this is reached.
            is TapToPaySessionException.PendingActivation -> null
            is TapToPaySessionException.NotPermitted -> failed(CONFIGURATION_REJECTED)
            is TapToPaySessionException.AttestationRequired -> failed(DEVICE_SETUP_REQUIRED)
            is TapToPaySessionException.NotRecoverable -> null
            is TapToPaySessionException.SetupAbandoned -> TapToPaySessionState.Idle
            is TapToPaySessionException.SetupFailed -> failed(SDK_INTERNAL_ERROR)
            is DeviceServiceException -> landingForService(failure)
            is DeviceActivationException -> landingForActivation(failure)
            is AttestationException -> landingForAttestation(failure)
            is DeviceKeyException -> landingForDeviceKey(failure)
            is SecureStorageException -> landingForStorage(failure)
            is DeviceIneligibleException -> failed(DEVICE_INELIGIBLE)
            is CardReaderException -> landingForReader(failure)
            is PayabliException -> landingForTransport(failure)
            else -> failed(SDK_INTERNAL_ERROR)
        }

    /**
     * An unusable entry point is refused under the same status as a device that owes activation, and its next
     * move is the opposite, so it is classified apart from that refusal.
     *
     * Nothing found discards nothing. More than one thing can be the one that was not found, and only one of
     * them means the stored identity is stale, so the safe landing is the one that keeps it.
     */
    private fun landingForService(failure: DeviceServiceException): TapToPaySessionState? =
        when (failure) {
            // Decided by the registration, before this is reached.
            is DeviceServiceException.Forbidden -> null
            is DeviceServiceException.EntryPointUnusable -> failed(CONFIGURATION_REJECTED)
            is DeviceServiceException.NotAttested -> failed(DEVICE_SETUP_REQUIRED)
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
            is DeviceActivationException.AttestationRevoked -> failed(DEVICE_SETUP_REQUIRED)
            is DeviceActivationException.DeviceUnknown -> failed(DEVICE_SETUP_REQUIRED)
            is DeviceActivationException.NotEnrolled -> failed(DEVICE_SETUP_REQUIRED)
            is DeviceActivationException.EntryNotAuthorized -> failed(CONFIGURATION_REJECTED)
            is DeviceActivationException.PaypointUnknown -> failed(CONFIGURATION_REJECTED)
            is DeviceActivationException.EntryPointUnusable -> failed(CONFIGURATION_REJECTED)
            is DeviceActivationException.ServiceFailed -> failed(SERVICE_UNAVAILABLE)
            // Each error is listed and not collapsed into one `else`, so a new classification fails to
            // compile here.
            is DeviceActivationException.CodeMalformed -> null
            is DeviceActivationException.CodeIncorrect -> null
            is DeviceActivationException.CodeExpired -> null
            is DeviceActivationException.AttemptsExhausted -> null
            is DeviceActivationException.CodeNotIssued -> null
            is DeviceActivationException.CodeUnreadable -> null
            is DeviceActivationException.DeviceNotPending -> null
            is DeviceActivationException.AssertionRejected -> null
            is DeviceActivationException.RequestRejected -> null
            is DeviceActivationException.Unclassified -> null
        }

    /** Naming the expired session is what lets a charge ask for a repair instead of a rebuild. */
    private fun landingForReader(failure: CardReaderException): TapToPaySessionState? =
        when (failure) {
            is CardReaderException.CredentialsUnusable -> failed(CONFIGURATION_REJECTED)
            is CardReaderException.ArmingFailed -> failed(SERVICE_UNAVAILABLE)
            // Calling again reaches nothing: the vendor refused the device, not the call.
            is CardReaderException.DeviceDenied -> failed(DEVICE_INELIGIBLE)
            is CardReaderException.SessionUnusable -> TapToPaySessionState.SessionExpired
            // The tap failed and the session did not. Nothing about the session changed.
            is CardReaderException.ReadFailed -> null
        }

    /**
     * The two the platform says to ask again about are service failures. Nothing about the device changed,
     * so a host is told to retry.
     *
     * Google Play missing, outdated or signed out is a change someone makes on the device, and attesting again
     * cannot make it. A reused challenge is this SDK's defect.
     */
    private fun landingForAttestation(failure: AttestationException): TapToPaySessionState =
        when (failure) {
            is AttestationException.Retryable -> failed(SERVICE_UNAVAILABLE)
            is AttestationException.Throttled -> failed(SERVICE_UNAVAILABLE)
            is AttestationException.Misconfigured -> failed(CONFIGURATION_REJECTED)
            is AttestationException.RemediationRequired -> failed(CONFIGURATION_REJECTED)
            is AttestationException.IntegrityFailed -> failed(DEVICE_SETUP_REQUIRED)
            is AttestationException.ChallengeReused -> failed(SDK_INTERNAL_ERROR)
        }

    /**
     * Storage whose key facility cannot answer is the same cause as a key store that cannot confirm the key.
     * The rest land where an unrecognised failure does.
     */
    private fun landingForStorage(failure: SecureStorageException): TapToPaySessionState =
        when (failure) {
            is SecureStorageException.CryptoUnavailable -> failed(DEVICE_KEY_UNAVAILABLE)
            is SecureStorageException.KeyInvalidated -> failed(SDK_INTERNAL_ERROR)
            is SecureStorageException.ValueUnreadable -> failed(SDK_INTERNAL_ERROR)
            is SecureStorageException.StorageUnavailable -> failed(SDK_INTERNAL_ERROR)
        }

    /**
     * A key that is gone is the identity being gone, which is a positive match: enrollment discards the
     * record before raising it, so the remedy is to attest again.
     *
     * The other two are not: a signature that failed and a key store that cannot confirm the key both leave
     * the key where it was, so neither says the identity is stale. The second is the phone's key facility
     * failing rather than a defect in the SDK, so it has its own reason.
     */
    private fun landingForDeviceKey(failure: DeviceKeyException): TapToPaySessionState =
        when (failure) {
            is DeviceKeyException.KeyLost -> failed(DEVICE_SETUP_REQUIRED)
            is DeviceKeyException.SigningFailed -> failed(SDK_INTERNAL_ERROR)
            is DeviceKeyException.CryptoUnavailable -> failed(DEVICE_KEY_UNAVAILABLE)
        }

    private fun landingForTransport(failure: PayabliException): TapToPaySessionState =
        when (failure.type) {
            PayabliErrorType.INVALID_CONFIGURATION -> failed(CONFIGURATION_REJECTED)
            PayabliErrorType.DECODING_ERROR -> failed(SDK_INTERNAL_ERROR)
            else -> failed(SERVICE_UNAVAILABLE)
        }

    private fun failed(reason: TapToPayFailureReason): TapToPaySessionState = TapToPaySessionState.Failed(reason)
}
