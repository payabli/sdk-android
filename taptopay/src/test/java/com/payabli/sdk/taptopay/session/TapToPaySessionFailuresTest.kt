package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.attestation.AttestationException
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceException
import com.payabli.sdk.taptopay.enrollment.DeviceActivationException
import com.payabli.sdk.taptopay.provider.DeviceIneligibleException
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.ATTESTATION_REQUIRED
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.CONFIGURATION_REJECTED
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.DEVICE_INELIGIBLE
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.SDK_INTERNAL_ERROR
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.SERVICE_UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Test

private val REASON = "server text"

private fun failed(reason: TapToPayFailureReason) = TapToPaySessionState.Failed(reason)

/**
 * Every failure a session can meet, and where it lands.
 *
 * The table is the contract: a host branches on the reason, so a failure that lands on the wrong one sends
 * it down a repair that cannot work. Each row names the failure so a wrong landing says which one moved.
 *
 * Two properties are asserted separately below the table, because both are easy to lose in a rewrite and
 * neither is visible in a single row: a landing of null leaves the session where it is, and only a failure
 * that names the attestation reaches [ATTESTATION_REQUIRED], which is the one landing that tells a host to
 * discard an identity.
 */
class TapToPaySessionFailuresTest {
    private val cases: List<Pair<Throwable, TapToPaySessionState?>> =
        listOf(
            TapToPaySessionException.PendingActivation() to TapToPaySessionState.PendingActivation,
            TapToPaySessionException.AttestationRequired() to failed(ATTESTATION_REQUIRED),
            TapToPaySessionException.NotRecoverable(TapToPaySessionState.Ready) to null,
            TapToPaySessionException.SetupAbandoned() to TapToPaySessionState.Idle,
            TapToPaySessionException.SetupFailed() to failed(SDK_INTERNAL_ERROR),
            DeviceServiceException.Forbidden(403, REASON) to TapToPaySessionState.PendingActivation,
            DeviceServiceException.NotAttested(401, REASON) to failed(ATTESTATION_REQUIRED),
            DeviceServiceException.EntryPointUnusable(403, REASON) to failed(CONFIGURATION_REJECTED),
            DeviceServiceException.NotFound(404, REASON) to failed(CONFIGURATION_REJECTED),
            DeviceServiceException.BadRequest(400, REASON) to failed(SDK_INTERNAL_ERROR),
            DeviceServiceException.ServerFailure(500, REASON) to failed(SERVICE_UNAVAILABLE),
            DeviceServiceException.Undecodable(null) to failed(SDK_INTERNAL_ERROR),
            DeviceServiceException.Unclassified(418, REASON) to failed(SERVICE_UNAVAILABLE),
            DeviceActivationException.AttestationRevoked(403, REASON) to failed(ATTESTATION_REQUIRED),
            DeviceActivationException.DeviceUnknown(404, REASON) to failed(ATTESTATION_REQUIRED),
            DeviceActivationException.NotEnrolled() to failed(ATTESTATION_REQUIRED),
            DeviceActivationException.EntryNotAuthorized(403, REASON) to failed(CONFIGURATION_REJECTED),
            DeviceActivationException.PaypointUnknown(404, REASON) to failed(CONFIGURATION_REJECTED),
            DeviceActivationException.EntryPointUnusable(403, REASON) to failed(CONFIGURATION_REJECTED),
            DeviceActivationException.ServiceFailed(500, REASON) to failed(SERVICE_UNAVAILABLE),
            // A wrong code leaves the session alone: the device still owes one. So does everything else the
            // caller can answer by sending the code again, which is the rest of this group.
            DeviceActivationException.CodeIncorrect(400, REASON) to null,
            DeviceActivationException.CodeMalformed() to null,
            DeviceActivationException.CodeExpired(400, REASON) to null,
            DeviceActivationException.AttemptsExhausted(400, REASON) to null,
            DeviceActivationException.CodeNotIssued(400, REASON) to null,
            DeviceActivationException.CodeUnreadable(400, REASON) to null,
            DeviceActivationException.DeviceNotPending(400, REASON) to null,
            DeviceActivationException.AssertionRejected(400, REASON) to null,
            DeviceActivationException.RequestRejected(400, REASON) to null,
            DeviceActivationException.Unclassified(418, REASON) to null,
            // The key is gone, so the identity is: enrollment discards the record before raising it.
            DeviceKeyException.KeyLost() to failed(ATTESTATION_REQUIRED),
            DeviceKeyException.SigningFailed() to failed(SDK_INTERNAL_ERROR),
            DeviceKeyException.CryptoUnavailable() to failed(SDK_INTERNAL_ERROR),
            AttestationException.Retryable(-1) to failed(SERVICE_UNAVAILABLE),
            AttestationException.Throttled(-8) to failed(SERVICE_UNAVAILABLE),
            AttestationException.Misconfigured(-2) to failed(CONFIGURATION_REJECTED),
            AttestationException.IntegrityFailed(-3) to failed(ATTESTATION_REQUIRED),
            DeviceIneligibleException("contactless payments are not supported") to failed(DEVICE_INELIGIBLE),
            CardReaderException.CredentialsUnusable("terminalId is blank") to failed(CONFIGURATION_REJECTED),
            CardReaderException.ArmingFailed(null) to failed(SERVICE_UNAVAILABLE),
            // The one landing a repair exists for, and the only failure that reaches it.
            CardReaderException.SessionUnusable(null) to TapToPaySessionState.SessionExpired,
            // A tap that did not complete says nothing about the session it ran on.
            CardReaderException.ReadFailed(null) to null,
            PayabliGenericException(PayabliErrorCode.PERMISSION_DENIED, REASON) to
                TapToPaySessionState.PendingActivation,
            PayabliGenericException(PayabliErrorCode.INVALID_CONFIGURATION, REASON) to
                failed(CONFIGURATION_REJECTED),
            PayabliGenericException(PayabliErrorCode.DECODING_ERROR, REASON) to failed(SDK_INTERNAL_ERROR),
            PayabliGenericException(PayabliErrorCode.NETWORK_ERROR, REASON) to failed(SERVICE_UNAVAILABLE),
            IllegalStateException("a defect in this SDK") to failed(SDK_INTERNAL_ERROR),
        )

    @Test
    fun `every failure lands where the table says`() {
        for ((failure, expected) in cases) {
            // Qualified, because two families carry a classification of the same simple name and a wrong
            // landing has to say which one moved.
            assertEquals(
                failure::class.qualifiedName ?: failure.javaClass.name,
                expected,
                TapToPaySessionFailures.landingFor(failure),
            )
        }
    }

    @Test
    fun `only a failure naming the attestation asks a host to discard the identity`() {
        // Read back from the classifier, not from the expectations above. Deriving it from the table would
        // assert the table against itself and pass with any production mapping.
        val discarding =
            cases
                .filter { TapToPaySessionFailures.landingFor(it.first) == failed(ATTESTATION_REQUIRED) }
                .map { it.first::class }
                .toSet()

        assertEquals(
            setOf(
                TapToPaySessionException.AttestationRequired::class,
                DeviceServiceException.NotAttested::class,
                DeviceActivationException.AttestationRevoked::class,
                DeviceActivationException.DeviceUnknown::class,
                DeviceActivationException.NotEnrolled::class,
                AttestationException.IntegrityFailed::class,
                DeviceKeyException.KeyLost::class,
            ),
            discarding,
        )
    }

    @Test
    fun `a landing of null is only for failures that change nothing about the session`() {
        val unchanged =
            cases
                .filter { TapToPaySessionFailures.landingFor(it.first) == null }
                .map { it.first::class }
                .toSet()

        assertEquals(
            setOf(
                TapToPaySessionException.NotRecoverable::class,
                DeviceActivationException.CodeIncorrect::class,
                DeviceActivationException.CodeMalformed::class,
                DeviceActivationException.CodeExpired::class,
                DeviceActivationException.AttemptsExhausted::class,
                DeviceActivationException.CodeNotIssued::class,
                DeviceActivationException.CodeUnreadable::class,
                DeviceActivationException.DeviceNotPending::class,
                DeviceActivationException.AssertionRejected::class,
                DeviceActivationException.RequestRejected::class,
                DeviceActivationException.Unclassified::class,
                CardReaderException.ReadFailed::class,
            ),
            unchanged,
        )
    }
}
