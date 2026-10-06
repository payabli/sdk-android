package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.taptopay.TapToPayErrorCodes
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

private val REASON = "server text"

private const val ACTIVATION_ID = "an-activation-id"

private val HELD = StoredRegistration.Held(ACTIVATION_ID)

private val UNREADABLE = StoredRegistration.Unreadable(SecureStorageException.CryptoUnavailable())

private val EVERY_REGISTRATION = listOf(HELD, StoredRegistration.None, UNREADABLE)

private fun failed(reason: TapToPayFailureReason) = TapToPaySessionState.Failed(reason)

/**
 * Every failure a session can meet, and where it lands.
 *
 * The table is the contract: a host branches on the reason, so a failure that lands on the wrong one sends
 * it down a repair that cannot work. Each row names the failure so a wrong landing says which one moved.
 *
 * The table is read against a held registration. What the other two registrations change is asserted
 * separately, against the same rows.
 *
 * Two properties are asserted separately below the table, because both are easy to lose in a rewrite and
 * neither is visible in a single row: a landing of null leaves the session where it is, and only a failure
 * that names the attestation reaches [DEVICE_SETUP_REQUIRED], which is the one landing that tells a host to
 * discard an identity.
 */
class TapToPaySessionFailuresTest {
    private val cases: List<Pair<Throwable, TapToPaySessionState?>> =
        listOf(
            TapToPaySessionException.PendingActivation() to TapToPaySessionState.PendingActivation(ACTIVATION_ID),
            TapToPaySessionException.NotPermitted(DeviceServiceException.Forbidden(403, REASON)) to
                failed(CONFIGURATION_REJECTED),
            TapToPaySessionException.AttestationRequired() to failed(DEVICE_SETUP_REQUIRED),
            TapToPaySessionException.NotRecoverable(TapToPaySessionState.Ready) to null,
            TapToPaySessionException.SetupAbandoned() to TapToPaySessionState.Idle,
            TapToPaySessionException.SetupFailed() to failed(SDK_INTERNAL_ERROR),
            DeviceServiceException.Forbidden(403, REASON) to TapToPaySessionState.PendingActivation(ACTIVATION_ID),
            DeviceServiceException.NotAttested(401, REASON) to failed(DEVICE_SETUP_REQUIRED),
            DeviceServiceException.EntryPointUnusable(403, REASON) to failed(CONFIGURATION_REJECTED),
            DeviceServiceException.NotFound(404, REASON) to failed(CONFIGURATION_REJECTED),
            DeviceServiceException.BadRequest(400, REASON) to failed(SDK_INTERNAL_ERROR),
            DeviceServiceException.ServerFailure(500, REASON) to failed(SERVICE_UNAVAILABLE),
            DeviceServiceException.Undecodable(null) to failed(SDK_INTERNAL_ERROR),
            DeviceServiceException.Unclassified(418, REASON) to failed(SERVICE_UNAVAILABLE),
            DeviceActivationException.AttestationRevoked(403, REASON) to failed(DEVICE_SETUP_REQUIRED),
            DeviceActivationException.DeviceUnknown(404, REASON) to failed(DEVICE_SETUP_REQUIRED),
            DeviceActivationException.NotEnrolled() to failed(DEVICE_SETUP_REQUIRED),
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
            DeviceKeyException.KeyLost() to failed(DEVICE_SETUP_REQUIRED),
            DeviceKeyException.SigningFailed() to failed(SDK_INTERNAL_ERROR),
            DeviceKeyException.CryptoUnavailable() to failed(DEVICE_KEY_UNAVAILABLE),
            AttestationException.Retryable(-1) to failed(SERVICE_UNAVAILABLE),
            AttestationException.Throttled(-8) to failed(SERVICE_UNAVAILABLE),
            AttestationException.Misconfigured(-2) to failed(CONFIGURATION_REJECTED),
            AttestationException.IntegrityFailed(-3) to failed(DEVICE_SETUP_REQUIRED),
            // Setting up again cannot install or update Google Play; someone has to.
            AttestationException.RemediationRequired(-14) to failed(CONFIGURATION_REJECTED),
            AttestationException.ChallengeReused() to failed(SDK_INTERNAL_ERROR),
            // The same cause as the key store's CryptoUnavailable, and the same landing.
            SecureStorageException.CryptoUnavailable() to failed(DEVICE_KEY_UNAVAILABLE),
            SecureStorageException.StorageUnavailable() to failed(SDK_INTERNAL_ERROR),
            SecureStorageException.KeyInvalidated() to failed(SDK_INTERNAL_ERROR),
            SecureStorageException.ValueUnreadable() to failed(SDK_INTERNAL_ERROR),
            DeviceIneligibleException(
                PayabliErrorType.DEVICE_HARDWARE_UNSUPPORTED,
                "contactless payments are not supported",
            ) to
                failed(DEVICE_INELIGIBLE),
            CardReaderException.CredentialsUnusable("terminalId is blank") to failed(CONFIGURATION_REJECTED),
            CardReaderException.ArmingFailed(null) to failed(SERVICE_UNAVAILABLE),
            // A refusal the vendor holds as state, so it is not the retryable landing above it.
            CardReaderException.DeviceDenied(null) to failed(DEVICE_INELIGIBLE),
            // The one landing a repair exists for, and the only failure that reaches it.
            CardReaderException.SessionUnusable(null) to TapToPaySessionState.SessionExpired,
            // A tap that did not complete says nothing about the session it ran on.
            CardReaderException.ReadFailed(null) to null,
            PayabliGenericException(PayabliErrorType.PERMISSION_DENIED, REASON) to
                TapToPaySessionState.PendingActivation(ACTIVATION_ID),
            PayabliGenericException(PayabliErrorType.INVALID_CONFIGURATION, REASON) to
                failed(CONFIGURATION_REJECTED),
            PayabliGenericException(PayabliErrorType.DECODING_ERROR, REASON) to failed(SDK_INTERNAL_ERROR),
            PayabliGenericException(PayabliErrorType.NETWORK_ERROR, REASON) to failed(SERVICE_UNAVAILABLE),
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
                TapToPaySessionFailures.landingFor(failure, HELD),
            )
        }
    }

    @Test
    fun `only a failure naming the attestation asks a host to discard the identity`() {
        // Read back from the classifier, not from the expectations above. Deriving it from the table would
        // assert the table against itself and pass with any production mapping.
        val discarding =
            cases
                .filter { TapToPaySessionFailures.landingFor(it.first, HELD) == failed(DEVICE_SETUP_REQUIRED) }
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
                .filter { TapToPaySessionFailures.landingFor(it.first, HELD) == null }
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

    @Test
    fun `only a refusal that owes activation reads the registration`() {
        // Read back from the classifier: the failures whose landing moves when nothing is registered.
        val consulting =
            cases
                .filter {
                    TapToPaySessionFailures.landingFor(it.first, HELD) !=
                        TapToPaySessionFailures.landingFor(it.first, StoredRegistration.None)
                }.map { it.first::class }
                .toSet()

        assertEquals(
            setOf(
                TapToPaySessionException.PendingActivation::class,
                DeviceServiceException.Forbidden::class,
                PayabliGenericException::class,
            ),
            consulting,
        )
    }

    @Test
    fun `a refusal that owes activation lands pending only on a held registration`() {
        for (failure in owingActivation()) {
            val name = failure::class.qualifiedName
            assertEquals(
                name,
                TapToPaySessionState.PendingActivation(ACTIVATION_ID),
                TapToPaySessionFailures.landingFor(failure, HELD),
            )
            assertEquals(
                name,
                failed(CONFIGURATION_REJECTED),
                TapToPaySessionFailures.landingFor(failure, StoredRegistration.None),
            )
            // A refused read lands where that refusal lands on its own, so storage has one table.
            for (refusal in storageRefusals()) {
                assertEquals(
                    "$name, ${refusal::class.simpleName}",
                    TapToPaySessionFailures.landingFor(refusal, HELD),
                    TapToPaySessionFailures.landingFor(failure, StoredRegistration.Unreadable(refusal)),
                )
            }
            assertEquals(name, failed(DEVICE_KEY_UNAVAILABLE), TapToPaySessionFailures.landingFor(failure, UNREADABLE))
        }
    }

    @Test
    fun `the failure raised carries the code of the state landed`() {
        for (failure in owingActivation()) {
            val name = failure::class.qualifiedName
            assertSame(name, failure, TapToPaySessionFailures.raisedFor(failure, HELD))

            val unregistered = TapToPaySessionFailures.raisedFor(failure, StoredRegistration.None)
            assertEquals(name, PayabliErrorType.PERMISSION_DENIED, TapToPayErrorCodes.typeFor(unregistered))

            assertSame(name, UNREADABLE.refusal, TapToPaySessionFailures.raisedFor(failure, UNREADABLE))
        }
    }

    @Test
    fun `any other failure is raised unchanged, whatever is registered`() {
        for ((failure, _) in cases) {
            val consultsRegistration =
                TapToPaySessionFailures.landingFor(failure, HELD) !=
                    TapToPaySessionFailures.landingFor(failure, StoredRegistration.None)
            if (failure !is Exception || consultsRegistration) continue
            for (registration in EVERY_REGISTRATION) {
                assertSame(
                    "${failure::class.qualifiedName} under $registration",
                    failure,
                    TapToPaySessionFailures.raisedFor(failure, registration),
                )
            }
        }
    }

    private fun storageRefusals(): List<SecureStorageException> =
        listOf(
            SecureStorageException.CryptoUnavailable(),
            SecureStorageException.StorageUnavailable(),
        )

    private fun owingActivation(): List<Exception> =
        listOf(
            TapToPaySessionException.PendingActivation(),
            DeviceServiceException.Forbidden(403, REASON),
            PayabliGenericException(PayabliErrorType.PERMISSION_DENIED, REASON),
        )
}
