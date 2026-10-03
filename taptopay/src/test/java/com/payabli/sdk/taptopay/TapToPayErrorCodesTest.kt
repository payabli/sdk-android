package com.payabli.sdk.taptopay

import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.model.PayabliErrorCode
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
    private val causes: List<Pair<Throwable, PayabliErrorCode>> =
        listOf(
            DeviceKeyException.CryptoUnavailable() to PayabliErrorCode.DEVICE_KEY_UNAVAILABLE,
            DeviceKeyException.KeyLost() to PayabliErrorCode.ATTESTATION_REQUIRED,
            DeviceKeyException.SigningFailed() to PayabliErrorCode.SDK_INTERNAL_ERROR,
            AttestationException.Unsupported(-1) to PayabliErrorCode.ATTESTATION_NOT_SUPPORTED,
            AttestationException.RemediationRequired(-14) to PayabliErrorCode.ATTESTATION_SERVICES_OUTDATED,
            AttestationException.IntegrityFailed(-5) to PayabliErrorCode.ATTESTATION_REFUSED,
            AttestationException.Retryable(-3) to PayabliErrorCode.ATTESTATION_UNAVAILABLE,
            AttestationException.Throttled(-8) to PayabliErrorCode.ATTESTATION_UNAVAILABLE,
            AttestationException.Misconfigured(null) to PayabliErrorCode.ATTESTATION_NOT_CONFIGURED,
            AttestationException.ChallengeReused() to PayabliErrorCode.SDK_INTERNAL_ERROR,
            TapToPaySessionException.PendingActivation() to PayabliErrorCode.DEVICE_PENDING_ACTIVATION,
            TapToPaySessionException.AttestationRequired() to PayabliErrorCode.ATTESTATION_REQUIRED,
            TapToPaySessionException.NotRecoverable(TapToPaySessionState.PendingActivation) to
                PayabliErrorCode.TERMINAL_NOT_READY,
            TapToPaySessionException.SetupAbandoned() to PayabliErrorCode.UNKNOWN,
            TapToPaySessionException.SetupFailed() to PayabliErrorCode.SDK_INTERNAL_ERROR,
            DeviceServiceException.BadRequest(400, "refused") to PayabliErrorCode.SDK_INTERNAL_ERROR,
            DeviceServiceException.NotAttested(401, "refused") to PayabliErrorCode.ATTESTATION_REQUIRED,
            DeviceServiceException.Forbidden(403, "refused") to PayabliErrorCode.DEVICE_PENDING_ACTIVATION,
            DeviceServiceException.EntryPointUnusable(403, "refused") to PayabliErrorCode.ENTRY_POINT_REFUSED,
            DeviceServiceException.NotFound(404, "refused") to PayabliErrorCode.ENTRY_POINT_REFUSED,
            DeviceServiceException.ServerFailure(500, "refused") to PayabliErrorCode.SERVER_ERROR,
            DeviceServiceException.Unclassified(null, "refused") to PayabliErrorCode.UNKNOWN,
            DeviceServiceException.Undecodable(null) to PayabliErrorCode.DECODING_ERROR,
            DeviceActivationException.CodeMalformed() to PayabliErrorCode.ACTIVATION_CODE_MALFORMED,
            DeviceActivationException.CodeIncorrect(400, "refused") to PayabliErrorCode.ACTIVATION_CODE_INCORRECT,
            DeviceActivationException.CodeExpired(400, "refused") to PayabliErrorCode.ACTIVATION_CODE_EXPIRED,
            DeviceActivationException.AttemptsExhausted(400, "refused") to
                PayabliErrorCode.ACTIVATION_ATTEMPTS_EXHAUSTED,
            DeviceActivationException.CodeNotIssued(400, "refused") to PayabliErrorCode.ACTIVATION_CODE_NOT_ISSUED,
            DeviceActivationException.CodeUnreadable(400, "refused") to PayabliErrorCode.ACTIVATION_CODE_NOT_ISSUED,
            DeviceActivationException.DeviceNotPending(400, "refused") to PayabliErrorCode.DEVICE_NOT_PENDING,
            DeviceActivationException.AssertionRejected(400, "refused") to PayabliErrorCode.ATTESTATION_REQUIRED,
            DeviceActivationException.RequestRejected(400, "refused") to PayabliErrorCode.SDK_INTERNAL_ERROR,
            DeviceActivationException.AttestationRevoked(401, "refused") to PayabliErrorCode.ATTESTATION_REQUIRED,
            DeviceActivationException.EntryNotAuthorized(401, "refused") to PayabliErrorCode.ENTRY_POINT_REFUSED,
            DeviceActivationException.PaypointUnknown(404, "refused") to PayabliErrorCode.ENTRY_POINT_REFUSED,
            DeviceActivationException.EntryPointUnusable(403, "refused") to PayabliErrorCode.ENTRY_POINT_REFUSED,
            DeviceActivationException.DeviceUnknown(404, "refused") to PayabliErrorCode.ATTESTATION_REQUIRED,
            DeviceActivationException.ServiceFailed(500, "refused") to PayabliErrorCode.SERVER_ERROR,
            DeviceActivationException.NotEnrolled() to PayabliErrorCode.ATTESTATION_REQUIRED,
            DeviceActivationException.Unclassified(400, "refused") to PayabliErrorCode.UNKNOWN,
            SecureStorageException.CryptoUnavailable() to PayabliErrorCode.SDK_INTERNAL_ERROR,
            SecureStorageException.StorageUnavailable() to PayabliErrorCode.SDK_INTERNAL_ERROR,
            DeviceIneligibleException(PayabliErrorCode.DEVICE_OS_UNSUPPORTED, "too old") to
                PayabliErrorCode.DEVICE_OS_UNSUPPORTED,
            DeviceIneligibleException(PayabliErrorCode.DEVICE_HARDWARE_UNSUPPORTED, "no radio") to
                PayabliErrorCode.DEVICE_HARDWARE_UNSUPPORTED,
            CardReaderException.CredentialsUnusable("blank") to PayabliErrorCode.READER_CREDENTIALS_UNUSABLE,
            CardReaderException.ArmingFailed(null) to PayabliErrorCode.READER_UNAVAILABLE,
            CardReaderException.DeviceDenied(null) to PayabliErrorCode.READER_DEVICE_REFUSED,
            CardReaderException.SessionUnusable(null) to PayabliErrorCode.READER_SESSION_EXPIRED,
            CardReaderException.ReadFailed(null) to PayabliErrorCode.TAP_NOT_COMPLETED,
            TTPTransactionException.NotEnabled() to PayabliErrorCode.CARD_PRESENT_NOT_ENABLED,
            TTPTransactionException.Refused("D", "refused") to PayabliErrorCode.PAYMENT_NOT_OPENED,
            TTPTransactionException.ServiceRejected("E", "refused") to PayabliErrorCode.PAYMENT_NOT_OPENED,
            TTPTransactionException.CardRefused("DECLINED") to PayabliErrorCode.CARD_DECLINED,
            TTPTransactionException.OutcomeUnknown(null) to PayabliErrorCode.PAYMENT_OUTCOME_UNKNOWN,
            TTPTransactionException.Undecodable() to PayabliErrorCode.DECODING_ERROR,
            TapToPayCallException.TerminalNotReady() to PayabliErrorCode.TERMINAL_NOT_READY,
            TapToPayCallException.NoDeviceToChargeAs() to PayabliErrorCode.ATTESTATION_REQUIRED,
            TapToPayCallException.PaymentNotHeld() to PayabliErrorCode.PAYMENT_NOT_HELD,
            ChargeKeyStoreFullException(4) to PayabliErrorCode.TOO_MANY_OPEN_CHARGES,
            IllegalArgumentException("a charge has to name the payer it is for") to PayabliErrorCode.VALIDATION_ERROR,
            IllegalStateException("refused transition") to PayabliErrorCode.SDK_INTERNAL_ERROR,
            RuntimeException("unexamined") to PayabliErrorCode.UNKNOWN,
        )

    @Test
    fun `every cause reaches a host under its catalog code`() {
        causes.forEach { (failure, expected) ->
            assertEquals(failure.javaClass.name, expected, TapToPayErrorCodes.codeFor(failure))
        }
    }

    @Test
    fun `every card-present code this platform can produce has a cause`() {
        // Nothing here asks a merchant to accept terms, and only the runner knows which failure came from the
        // close, so it names that code itself.
        val notFromACause = setOf(PayabliErrorCode.TERMS_NOT_ACCEPTED, PayabliErrorCode.PAYMENT_NOT_CLOSED)
        val cardPresent = PayabliErrorCode.entries.filter { it.number in 3001..3999 }.toSet()
        assertEquals(cardPresent - notFromACause, causes.map { it.second }.filter { it in cardPresent }.toSet())
    }

    @Test
    fun `a transport failure keeps its code, reason and detail`() {
        val transport = PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, "Unauthorized", "token rejected")

        val thrown =
            TapToPayErrorCodes.exceptionFor(
                transport,
                TapToPayErrorCodes.codeFor(transport),
                null,
                TapToPayCapture.NOT_CHARGED,
            )

        assertEquals(PayabliErrorCode.TOKEN_EXPIRED, thrown.code)
        assertEquals("Unauthorized", thrown.reason)
        assertEquals("token rejected", thrown.detail)
        assertSame(transport, thrown.cause)
    }

    @Test
    fun `a rejected credential and a refused reader are told apart by their code`() {
        val credential = PayabliGenericException(PayabliErrorCode.TOKEN_EXPIRED, "Unauthorized")
        val reader = CardReaderException.DeviceDenied(null)

        assertNotEquals(TapToPayErrorCodes.codeFor(credential), TapToPayErrorCodes.codeFor(reader))
        assertNotEquals(TapToPayErrorCodes.codeFor(credential).category, TapToPayErrorCodes.codeFor(reader).category)
    }

    @Test
    fun `a card-present cause carries its code's text as the reason and the service's words as the detail`() {
        val refused = TTPTransactionException.Refused("D", "the service's own words")

        val thrown =
            TapToPayErrorCodes.exceptionFor(
                refused,
                TapToPayErrorCodes.codeFor(refused),
                "txn",
                TapToPayCapture.NOT_CHARGED,
            )

        assertEquals(PayabliErrorCode.PAYMENT_NOT_OPENED.message, thrown.reason)
        assertEquals("the service's own words", thrown.detail)
        assertEquals(PayabliErrorCode.PAYMENT_NOT_OPENED.wireName, thrown.message)
    }

    @Test
    fun `a transport failure reported under another code takes that code's reason`() {
        val close = PayabliGenericException(PayabliErrorCode.NETWORK_ERROR, "Unreachable", "socket closed")

        val thrown =
            TapToPayErrorCodes.exceptionFor(
                close,
                PayabliErrorCode.PAYMENT_NOT_CLOSED,
                "txn",
                TapToPayCapture.CHARGED,
            )

        assertEquals(PayabliErrorCode.PAYMENT_NOT_CLOSED, thrown.code)
        assertEquals(PayabliErrorCode.PAYMENT_NOT_CLOSED.message, thrown.reason)
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
                    TapToPayErrorCodes.codeFor(failure),
                    null,
                    TapToPayCapture.NOT_CHARGED,
                )
            assertNull(failure.javaClass.name, thrown.detail)
        }
    }

    @Test
    fun `a cause with no service text carries no detail`() {
        val thrown =
            TapToPayErrorCodes.exceptionFor(
                CardReaderException.ReadFailed(null),
                PayabliErrorCode.TAP_NOT_COMPLETED,
                "txn",
                TapToPayCapture.UNKNOWN,
            )

        assertNull(thrown.detail)
    }
}
