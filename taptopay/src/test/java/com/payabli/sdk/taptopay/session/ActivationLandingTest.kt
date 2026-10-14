package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.model.PayabliRetryAfter
import com.payabli.sdk.taptopay.TapToPayErrorCodes
import com.payabli.sdk.taptopay.enrollment.DEVICE_ID
import com.payabli.sdk.taptopay.enrollment.RouteScript
import com.payabli.sdk.taptopay.enrollment.configBody
import com.payabli.sdk.taptopay.enrollment.decline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOT_ACTIVE = "Device is not active."

/** A 400 carrying no service envelope. */
private const val PROBLEM_BODY =
    """{"type":"https://tools.ietf.org/html/rfc9110#section-15.5.1",""" +
        """"title":"One or more validation errors occurred.",""" +
        """"status":400,"errors":{"ActivationCode":["The ActivationCode field is required."]}}"""

/**
 * Where a session waiting for an activation code lands when the activation fails, and what the caller is told.
 * Only a device that is gone or an unusable entry point moves it: otherwise the device still owes a code.
 */
class ActivationLandingTest {
    /** A session in pending activation, whose next activation is answered by [activate]. */
    private suspend fun pending(
        vararg activate: String,
        statusFor: (String) -> Int? = { null },
        headersFor: (String) -> Map<String, String> = { emptyMap() },
    ): SessionFixture {
        val fixture =
            SessionFixture(
                RouteScript(
                    RouteScript.CONFIG to listOf(decline(403, NOT_ACTIVE), configBody()),
                    RouteScript.ACTIVATE to activate.toList(),
                    statusFor = statusFor,
                    headersFor = headersFor,
                ),
            )
        fixture.seedRecord()
        runCatching { fixture.coordinator.initialize() }
        assertEquals(TapToPaySessionState.PendingActivation(DEVICE_ID), fixture.state)
        return fixture
    }

    private suspend fun activationFailure(fixture: SessionFixture): Throwable =
        runCatching { fixture.coordinator.activateDevice("123456") }.exceptionOrNull()
            ?: throw AssertionError("expected the activation to fail")

    private data class Refusal(
        val status: Int,
        val text: String,
        val reported: PayabliErrorType,
    )

    private val refusalsThatLeaveTheSession =
        listOf(
            Refusal(400, "Invalid activation code.", PayabliErrorType.ACTIVATION_CODE_INCORRECT),
            Refusal(400, "No active challenge for this device.", PayabliErrorType.ACTIVATION_CODE_NOT_ISSUED),
            Refusal(
                400,
                "Activation code has expired. Request a new challenge.",
                PayabliErrorType.ACTIVATION_CODE_EXPIRED,
            ),
            Refusal(
                400,
                "Too many failed activation attempts. Request a new challenge.",
                PayabliErrorType.ACTIVATION_ATTEMPTS_EXHAUSTED,
            ),
            Refusal(400, "Stored activation code is invalid.", PayabliErrorType.ACTIVATION_CODE_NOT_ISSUED),
            Refusal(
                403,
                "Device is not in a state that allows this operation.",
                PayabliErrorType.DEVICE_NOT_PENDING,
            ),
            Refusal(400, "X-App-Assertion header is required.", PayabliErrorType.SDK_INTERNAL_ERROR),
            Refusal(400, "X-App-Assertion is not valid base64.", PayabliErrorType.SDK_INTERNAL_ERROR),
            Refusal(
                400,
                "Assertion verification failed: timestamp outside the accepted window",
                PayabliErrorType.DEVICE_SETUP_REQUIRED,
            ),
            Refusal(409, "Attestation credential mismatch", PayabliErrorType.UNKNOWN),
            Refusal(400, "a refusal nobody has written down", PayabliErrorType.UNKNOWN),
        )

    @Test
    fun `a wrong code at its real status leaves the session waiting and reports an incorrect code`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = pending(decline(400, "Invalid activation code."))

            val failure = activationFailure(fixture)

            assertEquals(PayabliErrorType.ACTIVATION_CODE_INCORRECT, TapToPayErrorCodes.typeFor(failure))
            assertEquals(TapToPaySessionState.PendingActivation(DEVICE_ID), fixture.state)
        }

    @Test
    fun `a refusal that does not say the device is gone leaves the session waiting`() =
        runTest(timeout = TEST_TIMEOUT) {
            for (refusal in refusalsThatLeaveTheSession) {
                val fixture = pending(decline(refusal.status, refusal.text))

                val failure = activationFailure(fixture)

                assertEquals(refusal.text, refusal.reported, TapToPayErrorCodes.typeFor(failure))
                assertEquals(refusal.text, TapToPaySessionState.PendingActivation(DEVICE_ID), fixture.state)
                assertNotNull(refusal.text, fixture.enrollment.storedRecord())
            }
        }

    @Test
    fun `an unusable entry point moves the session to its configuration and keeps the binding`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = pending(decline(403, "Entry point is not available for this request."))

            val failure = activationFailure(fixture)

            assertEquals(PayabliErrorType.ENTRY_POINT_REFUSED, TapToPayErrorCodes.typeFor(failure))
            assertEquals(TapToPaySessionState.Failed(TapToPayFailureReason.CONFIGURATION_REJECTED), fixture.state)
            assertNotNull(fixture.enrollment.storedRecord())
        }

    @Test
    fun `a device the service no longer holds moves the session to setting it up again and discards the binding`() =
        runTest(timeout = TEST_TIMEOUT) {
            for ((status, text) in listOf(
                404 to "Device not found.",
                401 to "Device not attested or attestation revoked",
            )) {
                val fixture = pending(decline(status, text))

                val failure = activationFailure(fixture)

                assertEquals(text, PayabliErrorType.DEVICE_SETUP_REQUIRED, TapToPayErrorCodes.typeFor(failure))
                assertEquals(
                    text,
                    TapToPaySessionState.Failed(TapToPayFailureReason.DEVICE_SETUP_REQUIRED),
                    fixture.state,
                )
                assertNull(text, fixture.enrollment.storedRecord())
            }
        }

    @Test
    fun `a refusal from before the service reads the request leaves the session waiting and keeps its code`() =
        runTest(timeout = TEST_TIMEOUT) {
            for ((status, reported) in listOf(
                // A 403 with no envelope.
                403 to PayabliErrorType.PERMISSION_DENIED,
                // A 429 with no envelope and no Retry-After.
                429 to PayabliErrorType.RATE_LIMITED,
            )) {
                val fixture = pending("", statusFor = { path -> status.takeIf { path == RouteScript.ACTIVATE } })

                val failure = activationFailure(fixture)

                assertEquals("$status", reported, TapToPayErrorCodes.typeFor(failure))
                assertEquals("$status", TapToPaySessionState.PendingActivation(DEVICE_ID), fixture.state)
                assertNull("$status", (failure as? PayabliRetryAfter)?.retryAfterMillis)
            }
        }

    @Test
    fun `a request body the service would not read is this SDK's defect and leaves the session waiting`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = pending(PROBLEM_BODY, statusFor = { path -> 400.takeIf { path == RouteScript.ACTIVATE } })

            val failure = activationFailure(fixture)

            assertEquals(PayabliErrorType.SDK_INTERNAL_ERROR, TapToPayErrorCodes.typeFor(failure))
            assertEquals(TapToPaySessionState.PendingActivation(DEVICE_ID), fixture.state)
        }

    @Test
    fun `a service failure leaves the session waiting and carries the service's wait`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                pending(
                    decline(500, "Internal server error."),
                    headersFor = { path ->
                        if (path == RouteScript.ACTIVATE) mapOf("Retry-After" to "3") else emptyMap()
                    },
                )

            val failure = activationFailure(fixture)

            assertEquals(PayabliErrorType.SERVER_ERROR, TapToPayErrorCodes.typeFor(failure))
            assertEquals(3_000L, (failure as PayabliRetryAfter).retryAfterMillis)
            assertEquals(TapToPaySessionState.PendingActivation(DEVICE_ID), fixture.state)
        }

    @Test
    fun `no response leaves the session waiting and reports the network`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                pending(
                    "",
                    statusFor = { path ->
                        if (path == RouteScript.ACTIVATE) {
                            throw PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "no response")
                        }
                        null
                    },
                )

            val failure = activationFailure(fixture)

            assertEquals(PayabliErrorType.NETWORK_ERROR, TapToPayErrorCodes.typeFor(failure))
            assertEquals(TapToPaySessionState.PendingActivation(DEVICE_ID), fixture.state)
        }

    @Test
    fun `a cancelled activation stays a cancellation and leaves the session waiting`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                pending(
                    "",
                    statusFor = { path ->
                        if (path == RouteScript.ACTIVATE) throw CancellationException("withdrawn in flight")
                        null
                    },
                )

            val failure = activationFailure(fixture)

            assertTrue("$failure", failure is CancellationException)
            assertEquals(TapToPaySessionState.PendingActivation(DEVICE_ID), fixture.state)
        }

    @Test
    fun `a code sent again after its success was lost is refused as not pending, and setting up then reaches ready`() =
        runTest(timeout = TEST_TIMEOUT) {
            var activations = 0
            val fixture =
                pending(
                    "",
                    decline(403, "Device is not in a state that allows this operation."),
                    statusFor = { path ->
                        if (path == RouteScript.ACTIVATE && ++activations == 1) {
                            throw PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "the response was lost")
                        }
                        null
                    },
                )
            activationFailure(fixture)

            val retried = activationFailure(fixture)

            assertEquals(PayabliErrorType.DEVICE_NOT_PENDING, TapToPayErrorCodes.typeFor(retried))
            assertEquals(TapToPaySessionState.PendingActivation(DEVICE_ID), fixture.state)
            fixture.coordinator.initialize()
            assertEquals(TapToPaySessionState.Ready, fixture.state)
        }
}
