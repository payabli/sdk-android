package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.taptopay.TapToPayErrorCodes
import com.payabli.sdk.taptopay.enrollment.RouteScript
import com.payabli.sdk.taptopay.enrollment.challengeBody
import com.payabli.sdk.taptopay.enrollment.decline
import com.payabli.sdk.taptopay.enrollment.registerBody
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.CONFIGURATION_REJECTED
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.DEVICE_SETUP_REQUIRED
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.SDK_INTERNAL_ERROR
import com.payabli.sdk.taptopay.session.TapToPayFailureReason.SERVICE_UNAVAILABLE
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a host is told, and where the session lands, for each refusal `/attest` sends at its real status. */
class AttestRefusalLandingTest {
    private data class Refusal(
        val status: Int,
        val text: String,
        val reported: PayabliErrorType,
        val landing: TapToPayFailureReason,
    )

    private val refusals =
        listOf(
            // The verdict refused the device.
            Refusal(
                400,
                "Play Integrity verification failed: Missing tokenPayloadExternal in response.",
                PayabliErrorType.DEVICE_SETUP_REFUSED,
                DEVICE_SETUP_REQUIRED,
            ),
            Refusal(
                400,
                "Play Integrity verification failed: Missing nonce in token payload.",
                PayabliErrorType.DEVICE_SETUP_REFUSED,
                DEVICE_SETUP_REQUIRED,
            ),
            Refusal(
                400,
                "Play Integrity verification failed: Nonce mismatch.",
                PayabliErrorType.DEVICE_SETUP_REFUSED,
                DEVICE_SETUP_REQUIRED,
            ),
            Refusal(
                403,
                "Application identity could not be determined.",
                PayabliErrorType.DEVICE_SETUP_REFUSED,
                DEVICE_SETUP_REQUIRED,
            ),
            Refusal(
                400,
                "Sandbox attestations are not accepted in this environment.",
                PayabliErrorType.DEVICE_SETUP_REFUSED,
                DEVICE_SETUP_REQUIRED,
            ),
            // Nothing was decided about the device, and asking again repairs it.
            Refusal(
                400,
                "Challenge not found or already consumed.",
                PayabliErrorType.DEVICE_SETUP_UNAVAILABLE,
                SERVICE_UNAVAILABLE,
            ),
            Refusal(
                400,
                "Play Integrity verification failed: Play Integrity API error: TooManyRequests",
                PayabliErrorType.DEVICE_SETUP_UNAVAILABLE,
                SERVICE_UNAVAILABLE,
            ),
            // The registration this run made is gone, and registering again repairs it.
            Refusal(404, "Device not found.", PayabliErrorType.DEVICE_SETUP_REQUIRED, DEVICE_SETUP_REQUIRED),
            Refusal(
                403,
                "Device is not in a state that allows this operation.",
                PayabliErrorType.DEVICE_SETUP_REQUIRED,
                DEVICE_SETUP_REQUIRED,
            ),
            // A build this environment does not accept.
            Refusal(
                403,
                "Application is not recognized by Google Play.",
                PayabliErrorType.DEVICE_SETUP_NOT_CONFIGURED,
                CONFIGURATION_REJECTED,
            ),
            Refusal(
                403,
                "Entry point is not available for this request.",
                PayabliErrorType.ENTRY_POINT_REFUSED,
                CONFIGURATION_REJECTED,
            ),
            // A request this SDK built that the service would not read.
            Refusal(400, "PublicKey is not valid base64.", PayabliErrorType.SDK_INTERNAL_ERROR, SDK_INTERNAL_ERROR),
            Refusal(
                400,
                "PublicKey must be 65-byte X9.62 uncompressed format.",
                PayabliErrorType.SDK_INTERNAL_ERROR,
                SDK_INTERNAL_ERROR,
            ),
            Refusal(
                400,
                "Platform must be either Ios or Android.",
                PayabliErrorType.SDK_INTERNAL_ERROR,
                SDK_INTERNAL_ERROR,
            ),
            // One character off the verdict's text is not the verdict.
            Refusal(
                400,
                "Play Integrity verification failed: Nonce mismatch",
                PayabliErrorType.SDK_INTERNAL_ERROR,
                SDK_INTERNAL_ERROR,
            ),
        )

    @Test
    fun `each refusal from attest reaches the host with its own code and landing, and stores nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            for (refusal in refusals) {
                val fixture =
                    SessionFixture(
                        RouteScript(
                            RouteScript.CHALLENGE to listOf(challengeBody()),
                            RouteScript.REGISTER to listOf(registerBody()),
                            RouteScript.ATTEST to listOf(decline(refusal.status, refusal.text)),
                        ),
                    )

                val failure =
                    runCatching { fixture.coordinator.initialize() }.exceptionOrNull()
                        ?: throw AssertionError("${refusal.text}: expected initialize to fail")

                assertEquals(refusal.text, refusal.reported, TapToPayErrorCodes.typeFor(failure))
                assertEquals(refusal.text, TapToPaySessionState.Failed(refusal.landing), fixture.state)
                assertNull(refusal.text, fixture.enrollment.storedRecord())
            }
        }

    @Test
    fun `challenge and register refusing the entry point land on the configuration at the real status`() =
        runTest(timeout = TEST_TIMEOUT) {
            val refusal = decline(403, "Entry point is not available for this request.")
            for (script in listOf(
                RouteScript(RouteScript.CHALLENGE to listOf(refusal)),
                RouteScript(
                    RouteScript.CHALLENGE to listOf(challengeBody()),
                    RouteScript.REGISTER to listOf(refusal),
                ),
            )) {
                val fixture = SessionFixture(script)

                val failure = runCatching { fixture.coordinator.initialize() }.exceptionOrNull()

                assertEquals(PayabliErrorType.ENTRY_POINT_REFUSED, failure?.let(TapToPayErrorCodes::typeFor))
                assertEquals(TapToPaySessionState.Failed(CONFIGURATION_REJECTED), fixture.state)
            }
        }

    @Test
    fun `a request body the service would not read is this SDK's defect on every cold route`() =
        runTest(timeout = TEST_TIMEOUT) {
            val problem = """{"title":"One or more validation errors occurred.","status":400}"""
            for ((script, refusing) in listOf(
                RouteScript(RouteScript.CHALLENGE to listOf(problem), statusFor = { 400 }) to RouteScript.CHALLENGE,
                RouteScript(
                    RouteScript.CHALLENGE to listOf(challengeBody()),
                    RouteScript.REGISTER to listOf(problem),
                    statusFor = { path -> 400.takeIf { path == RouteScript.REGISTER } },
                ) to RouteScript.REGISTER,
                RouteScript(
                    RouteScript.CHALLENGE to listOf(challengeBody()),
                    RouteScript.REGISTER to listOf(registerBody()),
                    RouteScript.ATTEST to listOf(problem),
                    statusFor = { path -> 400.takeIf { path == RouteScript.ATTEST } },
                ) to RouteScript.ATTEST,
            )) {
                val fixture = SessionFixture(script)

                val failure = runCatching { fixture.coordinator.initialize() }.exceptionOrNull()

                assertEquals(refusing, PayabliErrorType.SDK_INTERNAL_ERROR, failure?.let(TapToPayErrorCodes::typeFor))
                assertEquals(refusing, TapToPaySessionState.Failed(SDK_INTERNAL_ERROR), fixture.state)
            }
        }
}
