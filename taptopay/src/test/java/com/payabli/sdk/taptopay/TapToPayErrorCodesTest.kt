package com.payabli.sdk.taptopay

import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.attestation.AttestationException
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceException
import com.payabli.sdk.taptopay.enrollment.DeviceActivationException
import com.payabli.sdk.taptopay.network.TTPTransactionException
import com.payabli.sdk.taptopay.provider.DeviceIneligibleException
import com.payabli.sdk.taptopay.session.TapToPaySessionException
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Every card-present cause against the code it reaches a host under.
 *
 * The rows are the catalog's Android sources. A cause classified the wrong way sends a host to the wrong
 * remedy, and nothing else in the suite reads the code.
 */
class TapToPayErrorCodesTest {
    private val causes: List<Pair<Throwable, PayabliErrorType>> =
        listOf(
            DeviceKeyException.CryptoUnavailable() to PayabliErrorType.DEVICE_KEY_UNAVAILABLE,
            DeviceKeyException.KeyLost() to PayabliErrorType.DEVICE_SETUP_REQUIRED,
            DeviceKeyException.SigningFailed() to PayabliErrorType.SDK_INTERNAL_ERROR,
            AttestationException.RemediationRequired(-14) to PayabliErrorType.DEVICE_SERVICES_OUTDATED,
            AttestationException.IntegrityFailed(-5) to PayabliErrorType.DEVICE_SETUP_REFUSED,
            AttestationException.Retryable(-3) to PayabliErrorType.DEVICE_SETUP_UNAVAILABLE,
            AttestationException.Throttled(-8) to PayabliErrorType.DEVICE_SETUP_UNAVAILABLE,
            AttestationException.Misconfigured(null) to PayabliErrorType.DEVICE_SETUP_NOT_CONFIGURED,
            AttestationException.ChallengeReused() to PayabliErrorType.SDK_INTERNAL_ERROR,
            TapToPaySessionException.PendingActivation() to PayabliErrorType.DEVICE_PENDING_ACTIVATION,
            TapToPaySessionException.AttestationRequired() to PayabliErrorType.DEVICE_SETUP_REQUIRED,
            TapToPaySessionException.NotRecoverable(TapToPaySessionState.PendingActivation) to
                PayabliErrorType.TERMINAL_NOT_READY,
            TapToPaySessionException.SetupAbandoned() to PayabliErrorType.UNKNOWN,
            TapToPaySessionException.SetupFailed() to PayabliErrorType.SDK_INTERNAL_ERROR,
            DeviceServiceException.BadRequest(400, "refused") to PayabliErrorType.SDK_INTERNAL_ERROR,
            DeviceServiceException.NotAttested(401, "refused") to PayabliErrorType.DEVICE_SETUP_REQUIRED,
            DeviceServiceException.Forbidden(403, "refused") to PayabliErrorType.DEVICE_PENDING_ACTIVATION,
            DeviceServiceException.EntryPointUnusable(403, "refused") to PayabliErrorType.ENTRY_POINT_REFUSED,
            DeviceServiceException.NotFound(404, "refused") to PayabliErrorType.ENTRY_POINT_REFUSED,
            DeviceServiceException.ServerFailure(500, "refused") to PayabliErrorType.SERVER_ERROR,
            DeviceServiceException.Unclassified(null, "refused") to PayabliErrorType.UNKNOWN,
            DeviceServiceException.Undecodable(null) to PayabliErrorType.DECODING_ERROR,
            DeviceActivationException.CodeMalformed() to PayabliErrorType.ACTIVATION_CODE_MALFORMED,
            DeviceActivationException.CodeIncorrect(400, "refused") to PayabliErrorType.ACTIVATION_CODE_INCORRECT,
            DeviceActivationException.CodeExpired(400, "refused") to PayabliErrorType.ACTIVATION_CODE_EXPIRED,
            DeviceActivationException.AttemptsExhausted(400, "refused") to
                PayabliErrorType.ACTIVATION_ATTEMPTS_EXHAUSTED,
            DeviceActivationException.CodeNotIssued(400, "refused") to PayabliErrorType.ACTIVATION_CODE_NOT_ISSUED,
            DeviceActivationException.CodeUnreadable(400, "refused") to PayabliErrorType.ACTIVATION_CODE_NOT_ISSUED,
            DeviceActivationException.DeviceNotPending(400, "refused") to PayabliErrorType.DEVICE_NOT_PENDING,
            DeviceActivationException.AssertionRejected(400, "refused") to PayabliErrorType.DEVICE_SETUP_REQUIRED,
            DeviceActivationException.RequestRejected(400, "refused") to PayabliErrorType.SDK_INTERNAL_ERROR,
            DeviceActivationException.AttestationRevoked(401, "refused") to PayabliErrorType.DEVICE_SETUP_REQUIRED,
            DeviceActivationException.EntryNotAuthorized(401, "refused") to PayabliErrorType.ENTRY_POINT_REFUSED,
            DeviceActivationException.PaypointUnknown(404, "refused") to PayabliErrorType.ENTRY_POINT_REFUSED,
            DeviceActivationException.EntryPointUnusable(403, "refused") to PayabliErrorType.ENTRY_POINT_REFUSED,
            DeviceActivationException.DeviceUnknown(404, "refused") to PayabliErrorType.DEVICE_SETUP_REQUIRED,
            DeviceActivationException.ServiceFailed(500, "refused") to PayabliErrorType.SERVER_ERROR,
            DeviceActivationException.NotEnrolled() to PayabliErrorType.DEVICE_SETUP_REQUIRED,
            DeviceActivationException.Unclassified(400, "refused") to PayabliErrorType.UNKNOWN,
            SecureStorageException.CryptoUnavailable() to PayabliErrorType.SDK_INTERNAL_ERROR,
            SecureStorageException.StorageUnavailable() to PayabliErrorType.SDK_INTERNAL_ERROR,
            DeviceIneligibleException(PayabliErrorType.DEVICE_OS_UNSUPPORTED, "too old") to
                PayabliErrorType.DEVICE_OS_UNSUPPORTED,
            DeviceIneligibleException(PayabliErrorType.DEVICE_HARDWARE_UNSUPPORTED, "no radio") to
                PayabliErrorType.DEVICE_HARDWARE_UNSUPPORTED,
            CardReaderException.CredentialsUnusable("blank") to PayabliErrorType.READER_CREDENTIALS_UNUSABLE,
            CardReaderException.ArmingFailed(null) to PayabliErrorType.READER_UNAVAILABLE,
            CardReaderException.DeviceDenied(null) to PayabliErrorType.READER_DEVICE_REFUSED,
            CardReaderException.SessionUnusable(null) to PayabliErrorType.READER_SESSION_EXPIRED,
            CardReaderException.ReadFailed(null) to PayabliErrorType.TAP_NOT_COMPLETED,
            TTPTransactionException.NotEnabled() to PayabliErrorType.CARD_PRESENT_NOT_ENABLED,
            TTPTransactionException.Refused("D", "refused") to PayabliErrorType.PAYMENT_NOT_OPENED,
            TTPTransactionException.ServiceRejected("E", "refused") to PayabliErrorType.PAYMENT_NOT_OPENED,
            TTPTransactionException.CardRefused("DECLINED") to PayabliErrorType.CARD_DECLINED,
            TTPTransactionException.OutcomeUnknown(null) to PayabliErrorType.PAYMENT_OUTCOME_UNKNOWN,
            TTPTransactionException.Undecodable() to PayabliErrorType.DECODING_ERROR,
            TapToPayCallException.TerminalNotReady() to PayabliErrorType.TERMINAL_NOT_READY,
            TapToPayCallException.NoDeviceToChargeAs() to PayabliErrorType.DEVICE_SETUP_REQUIRED,
            TapToPayCallException.PaymentNotHeld() to PayabliErrorType.PAYMENT_NOT_HELD,
            ChargeKeyStoreFullException(4) to PayabliErrorType.TOO_MANY_OPEN_CHARGES,
            TapToPayArgumentException("a charge has to name the payer it is for") to PayabliErrorType.VALIDATION_ERROR,
            IllegalArgumentException("a decoder's own complaint") to PayabliErrorType.UNKNOWN,
            IllegalStateException("refused transition") to PayabliErrorType.SDK_INTERNAL_ERROR,
            RuntimeException("unexamined") to PayabliErrorType.UNKNOWN,
        )

    @Test
    fun `every cause reaches a host under its catalog code`() {
        causes.forEach { (failure, expected) ->
            assertEquals(failure.javaClass.name, expected, TapToPayErrorCodes.typeFor(failure))
        }
    }

    @Test
    fun `every card-present code this platform can produce has a cause`() {
        // Nothing here asks a merchant to accept terms, and only the runner knows which failure came from the
        // close, so it names that code itself.
        // And no failure on this platform establishes that a device cannot attest at all: the integrity
        // service's "not available" also means it is not enabled or the store is out of date.
        val notFromACause =
            setOf(
                PayabliErrorType.TERMS_NOT_ACCEPTED,
                PayabliErrorType.PAYMENT_NOT_CLOSED,
                PayabliErrorType.DEVICE_SETUP_UNSUPPORTED,
                // Nothing on this platform raises it.
                PayabliErrorType.DEVICE_IDENTITY_UNAVAILABLE,
            )
        val cardPresent = PayabliErrorType.entries.filter { it.code in 3001..3999 }.toSet()
        assertEquals(cardPresent - notFromACause, causes.map { it.second }.filter { it in cardPresent }.toSet())
    }

    @Test
    fun `a transport failure keeps its code, reason and detail`() {
        val transport = PayabliGenericException(PayabliErrorType.TOKEN_EXPIRED, "Unauthorized", "token rejected")

        val thrown =
            TapToPayErrorCodes.exceptionFor(
                transport,
                TapToPayErrorCodes.typeFor(transport),
                null,
                TapToPayCapture.NOT_CHARGED,
            )

        assertEquals(PayabliErrorType.TOKEN_EXPIRED, thrown.type)
        assertEquals("Unauthorized", thrown.reason)
        assertEquals("token rejected", thrown.detail)
        assertSame(transport, thrown.cause)
    }

    @Test
    fun `a rejected credential and a refused reader are told apart by their code`() {
        val credential = PayabliGenericException(PayabliErrorType.TOKEN_EXPIRED, "Unauthorized")
        val reader = CardReaderException.DeviceDenied(null)

        assertNotEquals(TapToPayErrorCodes.typeFor(credential), TapToPayErrorCodes.typeFor(reader))
        assertNotEquals(TapToPayErrorCodes.typeFor(credential).category, TapToPayErrorCodes.typeFor(reader).category)
    }

    @Test
    fun `a card-present cause carries its code's text as the reason and the service's words as the detail`() {
        val refused = TTPTransactionException.Refused("D", "the service's own words")

        val thrown =
            TapToPayErrorCodes.exceptionFor(
                refused,
                TapToPayErrorCodes.typeFor(refused),
                "txn",
                TapToPayCapture.NOT_CHARGED,
            )

        assertEquals(PayabliErrorType.PAYMENT_NOT_OPENED.message, thrown.reason)
        assertEquals("the service's own words", thrown.detail)
        assertEquals(PayabliErrorType.PAYMENT_NOT_OPENED.message, thrown.message)
    }

    @Test
    fun `a transport failure reported under another code takes that code's reason`() {
        val close = PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "Unreachable", "socket closed")

        val thrown =
            TapToPayErrorCodes.exceptionFor(
                close,
                PayabliErrorType.PAYMENT_NOT_CLOSED,
                "txn",
                TapToPayCapture.CHARGED,
            )

        assertEquals(PayabliErrorType.PAYMENT_NOT_CLOSED, thrown.type)
        assertEquals(PayabliErrorType.PAYMENT_NOT_CLOSED.message, thrown.reason)
        assertEquals("socket closed", thrown.detail)
    }

    @Test
    fun `an empty or SDK-written reason is never offered as the service's words`() {
        listOf(
            DeviceActivationException.CodeMalformed(),
            DeviceActivationException.NotEnrolled(),
            DeviceServiceException.Forbidden(403, ""),
            DeviceServiceException.Undecodable(null),
        ).forEach { failure ->
            val thrown =
                TapToPayErrorCodes.exceptionFor(
                    failure,
                    TapToPayErrorCodes.typeFor(failure),
                    null,
                    TapToPayCapture.NOT_CHARGED,
                )
            assertNull(failure.javaClass.name, thrown.detail)
        }
    }

    @Test
    fun `the service's words reach the detail when the session wrapped its refusal`() {
        val wrapped =
            TapToPaySessionException.PendingActivation(
                DeviceServiceException.Forbidden(403, "the service's own words"),
            )

        val thrown =
            TapToPayErrorCodes.exceptionFor(
                wrapped,
                TapToPayErrorCodes.typeFor(wrapped),
                null,
                TapToPayCapture.NOT_CHARGED,
            )

        assertEquals(PayabliErrorType.DEVICE_PENDING_ACTIVATION, thrown.type)
        assertEquals("the service's own words", thrown.detail)
    }

    @Test
    fun `a cause with no service text carries no detail`() {
        val thrown =
            TapToPayErrorCodes.exceptionFor(
                CardReaderException.ReadFailed(null),
                PayabliErrorType.TAP_NOT_COMPLETED,
                "txn",
                TapToPayCapture.UNKNOWN,
            )

        assertNull(thrown.detail)
    }
}
