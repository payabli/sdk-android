package com.payabli.sdk.taptopay

import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.model.PayabliErrorCode
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
 * member added to one fails to compile here until it has a code. What reaches the end unrecognised is [PayabliErrorCode.UNKNOWN], whose category tells a
 * host to check before repeating: an unexamined failure is the one whose outcome nobody knows.
 */
internal object TapToPayErrorCodes {
    fun codeFor(failure: Throwable): PayabliErrorCode =
        when (failure) {
            is PayabliException -> failure.code
            is TapToPayCallException -> callCode(failure)
            is TapToPaySessionException -> sessionCode(failure)
            is DeviceServiceException -> serviceCode(failure)
            is DeviceActivationException -> activationCode(failure)
            is AttestationException -> attestationCode(failure)
            is DeviceKeyException -> deviceKeyCode(failure)
            is SecureStorageException -> PayabliErrorCode.SDK_INTERNAL_ERROR
            is DeviceIneligibleException -> failure.code
            is CardReaderException -> readerCode(failure)
            is TTPTransactionException -> transactionCode(failure)
            is ChargeKeyStoreFullException -> PayabliErrorCode.TOO_MANY_OPEN_CHARGES
            is IllegalArgumentException -> PayabliErrorCode.VALIDATION_ERROR
            // A guard inside this SDK that refused its own sequence, which is a defect rather than a refusal.
            is IllegalStateException -> PayabliErrorCode.SDK_INTERNAL_ERROR
            else -> PayabliErrorCode.UNKNOWN
        }

    /**
     * [failure] as a host receives it, under [code].
     *
     * The reason is [code]'s own fixed text, except for a failure the transport raised under the same code, which
     * keeps the reason it was raised with. Text a service sent rides in the detail, never in the reason.
     */
    fun exceptionFor(
        failure: Throwable,
        code: PayabliErrorCode,
        paymentTransId: String?,
        capture: TapToPayCapture,
    ): TapToPayException =
        TapToPayException.of(
            code = code,
            reason = (failure as? PayabliException)?.takeIf { it.code == code }?.reason ?: code.message,
            cause = failure,
            detail = serviceTextOf(failure),
            paymentTransId = paymentTransId,
            capture = capture,
        )

    private fun serviceTextOf(failure: Throwable): String? =
        when (failure) {
            is PayabliException -> failure.detail
            // The SDK's own wording for a body it could not read, not anything the service said.
            is DeviceServiceException.Undecodable -> null
            is DeviceServiceException -> failure.reason
            is DeviceActivationException -> failure.reason
            is TTPTransactionException -> failure.reason
            else -> null
        }?.takeIf { it.isNotBlank() }

    private fun callCode(failure: TapToPayCallException): PayabliErrorCode =
        when (failure) {
            is TapToPayCallException.TerminalNotReady -> PayabliErrorCode.TERMINAL_NOT_READY
            is TapToPayCallException.NoDeviceToChargeAs -> PayabliErrorCode.ATTESTATION_REQUIRED
            is TapToPayCallException.PaymentNotHeld -> PayabliErrorCode.PAYMENT_NOT_HELD
        }

    private fun sessionCode(failure: TapToPaySessionException): PayabliErrorCode =
        when (failure) {
            is TapToPaySessionException.PendingActivation -> PayabliErrorCode.DEVICE_PENDING_ACTIVATION
            is TapToPaySessionException.AttestationRequired -> PayabliErrorCode.ATTESTATION_REQUIRED
            is TapToPaySessionException.NotRecoverable -> PayabliErrorCode.TERMINAL_NOT_READY
            // Nothing was sent for this caller, and this cause has no code of its own.
            is TapToPaySessionException.SetupAbandoned -> PayabliErrorCode.UNKNOWN
            is TapToPaySessionException.SetupFailed -> PayabliErrorCode.SDK_INTERNAL_ERROR
        }

    private fun serviceCode(failure: DeviceServiceException): PayabliErrorCode =
        when (failure) {
            is DeviceServiceException.BadRequest -> PayabliErrorCode.SDK_INTERNAL_ERROR
            is DeviceServiceException.NotAttested -> PayabliErrorCode.ATTESTATION_REQUIRED
            is DeviceServiceException.Forbidden -> PayabliErrorCode.DEVICE_PENDING_ACTIVATION
            is DeviceServiceException.EntryPointUnusable -> PayabliErrorCode.ENTRY_POINT_REFUSED
            is DeviceServiceException.NotFound -> PayabliErrorCode.ENTRY_POINT_REFUSED
            is DeviceServiceException.ServerFailure -> PayabliErrorCode.SERVER_ERROR
            is DeviceServiceException.Unclassified -> PayabliErrorCode.UNKNOWN
            is DeviceServiceException.Undecodable -> PayabliErrorCode.DECODING_ERROR
        }

    private fun activationCode(failure: DeviceActivationException): PayabliErrorCode =
        when (failure) {
            is DeviceActivationException.CodeMalformed -> PayabliErrorCode.ACTIVATION_CODE_MALFORMED
            is DeviceActivationException.CodeIncorrect -> PayabliErrorCode.ACTIVATION_CODE_INCORRECT
            is DeviceActivationException.CodeExpired -> PayabliErrorCode.ACTIVATION_CODE_EXPIRED
            is DeviceActivationException.AttemptsExhausted -> PayabliErrorCode.ACTIVATION_ATTEMPTS_EXHAUSTED
            is DeviceActivationException.CodeNotIssued -> PayabliErrorCode.ACTIVATION_CODE_NOT_ISSUED
            is DeviceActivationException.CodeUnreadable -> PayabliErrorCode.ACTIVATION_CODE_NOT_ISSUED
            is DeviceActivationException.DeviceNotPending -> PayabliErrorCode.DEVICE_NOT_PENDING
            is DeviceActivationException.AssertionRejected -> PayabliErrorCode.ATTESTATION_REQUIRED
            is DeviceActivationException.RequestRejected -> PayabliErrorCode.SDK_INTERNAL_ERROR
            is DeviceActivationException.AttestationRevoked -> PayabliErrorCode.ATTESTATION_REQUIRED
            is DeviceActivationException.EntryNotAuthorized -> PayabliErrorCode.ENTRY_POINT_REFUSED
            is DeviceActivationException.PaypointUnknown -> PayabliErrorCode.ENTRY_POINT_REFUSED
            is DeviceActivationException.EntryPointUnusable -> PayabliErrorCode.ENTRY_POINT_REFUSED
            is DeviceActivationException.DeviceUnknown -> PayabliErrorCode.ATTESTATION_REQUIRED
            is DeviceActivationException.ServiceFailed -> PayabliErrorCode.SERVER_ERROR
            is DeviceActivationException.NotEnrolled -> PayabliErrorCode.ATTESTATION_REQUIRED
            is DeviceActivationException.Unclassified -> PayabliErrorCode.UNKNOWN
        }

    private fun attestationCode(failure: AttestationException): PayabliErrorCode =
        when (failure) {
            is AttestationException.Unsupported -> PayabliErrorCode.ATTESTATION_NOT_SUPPORTED
            is AttestationException.RemediationRequired -> PayabliErrorCode.ATTESTATION_SERVICES_OUTDATED
            is AttestationException.Retryable -> PayabliErrorCode.ATTESTATION_UNAVAILABLE
            is AttestationException.Throttled -> PayabliErrorCode.ATTESTATION_UNAVAILABLE
            is AttestationException.IntegrityFailed -> PayabliErrorCode.ATTESTATION_REFUSED
            is AttestationException.Misconfigured -> PayabliErrorCode.ATTESTATION_NOT_CONFIGURED
            is AttestationException.ChallengeReused -> PayabliErrorCode.SDK_INTERNAL_ERROR
        }

    private fun deviceKeyCode(failure: DeviceKeyException): PayabliErrorCode =
        when (failure) {
            is DeviceKeyException.KeyLost -> PayabliErrorCode.ATTESTATION_REQUIRED
            is DeviceKeyException.SigningFailed -> PayabliErrorCode.SDK_INTERNAL_ERROR
            is DeviceKeyException.CryptoUnavailable -> PayabliErrorCode.DEVICE_KEY_UNAVAILABLE
        }

    private fun readerCode(failure: CardReaderException): PayabliErrorCode =
        when (failure) {
            is CardReaderException.CredentialsUnusable -> PayabliErrorCode.READER_CREDENTIALS_UNUSABLE
            is CardReaderException.ArmingFailed -> PayabliErrorCode.READER_UNAVAILABLE
            is CardReaderException.DeviceDenied -> PayabliErrorCode.READER_DEVICE_REFUSED
            is CardReaderException.SessionUnusable -> PayabliErrorCode.READER_SESSION_EXPIRED
            is CardReaderException.ReadFailed -> PayabliErrorCode.TAP_NOT_COMPLETED
        }

    private fun transactionCode(failure: TTPTransactionException): PayabliErrorCode =
        when (failure) {
            is TTPTransactionException.NotEnabled -> PayabliErrorCode.CARD_PRESENT_NOT_ENABLED
            is TTPTransactionException.Refused -> PayabliErrorCode.PAYMENT_NOT_OPENED
            is TTPTransactionException.ServiceRejected -> PayabliErrorCode.PAYMENT_NOT_OPENED
            is TTPTransactionException.CardRefused -> PayabliErrorCode.CARD_DECLINED
            is TTPTransactionException.OutcomeUnknown -> PayabliErrorCode.PAYMENT_OUTCOME_UNKNOWN
            is TTPTransactionException.Undecodable -> PayabliErrorCode.DECODING_ERROR
        }
}
