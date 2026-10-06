package com.payabli.sdk.taptopay

import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.attestation.AttestationException
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceException
import com.payabli.sdk.taptopay.enrollment.DeviceActivationException
import com.payabli.sdk.taptopay.network.TTPTransactionException
import com.payabli.sdk.taptopay.provider.DeviceIneligibleException
import com.payabli.sdk.taptopay.session.TapToPaySessionException

/**
 * Which catalog code a card-present failure reaches a host under, and the one place that decides it.
 *
 * A failure the transport already classified keeps its code. Every other cause is named here, family by
 * family. A family whose members are different causes is matched member by member without an `else`, so a
 * member added to one fails to compile here until it has a code. What reaches the end unrecognised is [PayabliErrorType.UNKNOWN], whose category tells a
 * host to check before repeating: an unexamined failure is the one whose outcome nobody knows.
 */
internal object TapToPayErrorCodes {
    fun typeFor(failure: Throwable): PayabliErrorType =
        when (failure) {
            is PayabliException -> failure.type
            is TapToPayCallException -> callCode(failure)
            is TapToPaySessionException -> sessionCode(failure)
            is DeviceServiceException -> serviceCode(failure)
            is DeviceActivationException -> activationCode(failure)
            is AttestationException -> attestationCode(failure)
            is DeviceKeyException -> deviceKeyCode(failure)
            is SecureStorageException -> storageCode(failure)
            is DeviceIneligibleException -> failure.type
            is CardReaderException -> readerCode(failure)
            is TTPTransactionException -> transactionCode(failure)
            is ChargeKeyStoreFullException -> PayabliErrorType.TOO_MANY_OPEN_CHARGES
            is TapToPayArgumentException -> PayabliErrorType.VALIDATION_ERROR
            // A guard inside this SDK that refused its own sequence, which is a defect rather than a refusal.
            is IllegalStateException -> PayabliErrorType.SDK_INTERNAL_ERROR
            else -> PayabliErrorType.UNKNOWN
        }

    /**
     * [failure] as a host receives it, under [type].
     *
     * The reason is [type]'s own fixed text, except for a failure the transport raised under the same type, which
     * keeps the reason it was raised with. Text a service sent rides in the detail, never in the reason.
     */
    fun exceptionFor(
        failure: Throwable,
        type: PayabliErrorType,
        paymentTransId: String?,
        capture: TapToPayCapture,
    ): TapToPayException =
        TapToPayException.of(
            type = type,
            reason = (failure as? PayabliException)?.takeIf { it.type == type }?.reason ?: type.message,
            cause = failure,
            detail = serviceTextOf(failure),
            paymentTransId = paymentTransId,
            capture = capture,
        )

    /**
     * The first text a service sent, anywhere in [failure]'s chain. A session refusal wraps the service's own
     * failure, so the words are on the cause rather than on the failure a host is told about.
     */
    private fun serviceTextOf(failure: Throwable): String? =
        generateSequence(failure) { it.cause }.take(MAX_CAUSES).firstNotNullOfOrNull(::ownServiceTextOf)

    private fun ownServiceTextOf(failure: Throwable): String? =
        when (failure) {
            is PayabliException -> failure.detail
            // The SDK's own wording for a body it could not read, not anything the service said.
            is DeviceServiceException.Undecodable -> null
            is DeviceServiceException -> failure.reason
            is DeviceActivationException -> failure.reason
            is TTPTransactionException -> failure.reason
            else -> null
        }?.takeIf { it.isNotBlank() }

    private fun callCode(failure: TapToPayCallException): PayabliErrorType =
        when (failure) {
            is TapToPayCallException.TerminalNotReady -> PayabliErrorType.TERMINAL_NOT_READY
            is TapToPayCallException.NoDeviceToChargeAs -> PayabliErrorType.DEVICE_SETUP_REQUIRED
            is TapToPayCallException.PaymentNotHeld -> PayabliErrorType.PAYMENT_NOT_HELD
        }

    private fun sessionCode(failure: TapToPaySessionException): PayabliErrorType =
        when (failure) {
            is TapToPaySessionException.PendingActivation -> PayabliErrorType.DEVICE_PENDING_ACTIVATION
            is TapToPaySessionException.AttestationRequired -> PayabliErrorType.DEVICE_SETUP_REQUIRED
            is TapToPaySessionException.NotRecoverable -> PayabliErrorType.TERMINAL_NOT_READY
            // Nothing was sent for this caller, and this cause has no code of its own.
            is TapToPaySessionException.SetupAbandoned -> PayabliErrorType.UNKNOWN
            is TapToPaySessionException.SetupFailed -> PayabliErrorType.SDK_INTERNAL_ERROR
        }

    private fun serviceCode(failure: DeviceServiceException): PayabliErrorType =
        when (failure) {
            is DeviceServiceException.BadRequest -> PayabliErrorType.SDK_INTERNAL_ERROR
            is DeviceServiceException.NotAttested -> PayabliErrorType.DEVICE_SETUP_REQUIRED
            is DeviceServiceException.Forbidden -> PayabliErrorType.DEVICE_PENDING_ACTIVATION
            is DeviceServiceException.EntryPointUnusable -> PayabliErrorType.ENTRY_POINT_REFUSED
            is DeviceServiceException.NotFound -> PayabliErrorType.ENTRY_POINT_REFUSED
            is DeviceServiceException.ServerFailure -> PayabliErrorType.SERVER_ERROR
            is DeviceServiceException.Unclassified -> PayabliErrorType.UNKNOWN
            is DeviceServiceException.Undecodable -> PayabliErrorType.DECODING_ERROR
        }

    private fun activationCode(failure: DeviceActivationException): PayabliErrorType =
        when (failure) {
            is DeviceActivationException.CodeMalformed -> PayabliErrorType.ACTIVATION_CODE_MALFORMED
            is DeviceActivationException.CodeIncorrect -> PayabliErrorType.ACTIVATION_CODE_INCORRECT
            is DeviceActivationException.CodeExpired -> PayabliErrorType.ACTIVATION_CODE_EXPIRED
            is DeviceActivationException.AttemptsExhausted -> PayabliErrorType.ACTIVATION_ATTEMPTS_EXHAUSTED
            is DeviceActivationException.CodeNotIssued -> PayabliErrorType.ACTIVATION_CODE_NOT_ISSUED
            is DeviceActivationException.CodeUnreadable -> PayabliErrorType.ACTIVATION_CODE_NOT_ISSUED
            is DeviceActivationException.DeviceNotPending -> PayabliErrorType.DEVICE_NOT_PENDING
            is DeviceActivationException.AssertionRejected -> PayabliErrorType.DEVICE_SETUP_REQUIRED
            is DeviceActivationException.RequestRejected -> PayabliErrorType.SDK_INTERNAL_ERROR
            is DeviceActivationException.AttestationRevoked -> PayabliErrorType.DEVICE_SETUP_REQUIRED
            is DeviceActivationException.EntryNotAuthorized -> PayabliErrorType.ENTRY_POINT_REFUSED
            is DeviceActivationException.PaypointUnknown -> PayabliErrorType.ENTRY_POINT_REFUSED
            is DeviceActivationException.EntryPointUnusable -> PayabliErrorType.ENTRY_POINT_REFUSED
            is DeviceActivationException.DeviceUnknown -> PayabliErrorType.DEVICE_SETUP_REQUIRED
            is DeviceActivationException.ServiceFailed -> PayabliErrorType.SERVER_ERROR
            is DeviceActivationException.NotEnrolled -> PayabliErrorType.DEVICE_SETUP_REQUIRED
            is DeviceActivationException.Unclassified -> PayabliErrorType.UNKNOWN
        }

    private fun attestationCode(failure: AttestationException): PayabliErrorType =
        when (failure) {
            is AttestationException.RemediationRequired -> PayabliErrorType.DEVICE_SERVICES_OUTDATED
            is AttestationException.Retryable -> PayabliErrorType.DEVICE_SETUP_UNAVAILABLE
            is AttestationException.Throttled -> PayabliErrorType.DEVICE_SETUP_UNAVAILABLE
            is AttestationException.IntegrityFailed -> PayabliErrorType.DEVICE_SETUP_REFUSED
            is AttestationException.Misconfigured -> PayabliErrorType.DEVICE_SETUP_NOT_CONFIGURED
            is AttestationException.ChallengeReused -> PayabliErrorType.SDK_INTERNAL_ERROR
        }

    private fun deviceKeyCode(failure: DeviceKeyException): PayabliErrorType =
        when (failure) {
            is DeviceKeyException.KeyLost -> PayabliErrorType.DEVICE_SETUP_REQUIRED
            is DeviceKeyException.SigningFailed -> PayabliErrorType.SDK_INTERNAL_ERROR
            is DeviceKeyException.CryptoUnavailable -> PayabliErrorType.DEVICE_KEY_UNAVAILABLE
        }

    private fun storageCode(failure: SecureStorageException): PayabliErrorType =
        when (failure) {
            is SecureStorageException.CryptoUnavailable -> PayabliErrorType.DEVICE_KEY_UNAVAILABLE
            is SecureStorageException.KeyInvalidated -> PayabliErrorType.SDK_INTERNAL_ERROR
            is SecureStorageException.ValueUnreadable -> PayabliErrorType.SDK_INTERNAL_ERROR
            is SecureStorageException.StorageUnavailable -> PayabliErrorType.SDK_INTERNAL_ERROR
        }

    private fun readerCode(failure: CardReaderException): PayabliErrorType =
        when (failure) {
            is CardReaderException.CredentialsUnusable -> PayabliErrorType.READER_CREDENTIALS_UNUSABLE
            is CardReaderException.ArmingFailed -> PayabliErrorType.READER_UNAVAILABLE
            is CardReaderException.DeviceDenied -> PayabliErrorType.READER_DEVICE_REFUSED
            is CardReaderException.SessionUnusable -> PayabliErrorType.READER_SESSION_EXPIRED
            is CardReaderException.ReadFailed -> PayabliErrorType.TAP_NOT_COMPLETED
        }

    private fun transactionCode(failure: TTPTransactionException): PayabliErrorType =
        when (failure) {
            is TTPTransactionException.NotEnabled -> PayabliErrorType.CARD_PRESENT_NOT_ENABLED
            is TTPTransactionException.Refused -> PayabliErrorType.PAYMENT_NOT_OPENED
            is TTPTransactionException.ServiceRejected -> PayabliErrorType.PAYMENT_NOT_OPENED
            is TTPTransactionException.CardRefused -> PayabliErrorType.CARD_DECLINED
            is TTPTransactionException.OutcomeUnknown -> PayabliErrorType.PAYMENT_OUTCOME_UNKNOWN
            is TTPTransactionException.Undecodable -> PayabliErrorType.DECODING_ERROR
        }

    /** Bounds the walk, so a cause chain that loops back on itself cannot hold the call. */
    private const val MAX_CAUSES = 8
}
