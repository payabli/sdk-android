package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.taptopay.TapToPayErrorCodes
import com.payabli.sdk.taptopay.enrollment.DEVICE_ID
import com.payabli.sdk.taptopay.enrollment.RouteScript
import com.payabli.sdk.taptopay.enrollment.activateBody
import com.payabli.sdk.taptopay.enrollment.attestBody
import com.payabli.sdk.taptopay.enrollment.challengeBody
import com.payabli.sdk.taptopay.enrollment.configBody
import com.payabli.sdk.taptopay.enrollment.decline
import com.payabli.sdk.taptopay.enrollment.registerBody
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val UNAUTHORIZED = 401
private const val NOT_ACTIVE = "Device is not active."

/**
 * A device route that refuses the token it was sent with a plain 401: the session renews the token between
 * runs and runs once more from the first call. A second refusal reaches the caller as an expired token.
 */
class SessionTokenRenewalTest {
    private val recorded = mutableListOf<Pair<String, Map<String, String>>>()

    @Before
    fun record() = TelemetryRecorders.install { event, properties -> recorded += event to properties }

    @After
    fun stopRecording() = TelemetryRecorders.clear()

    private fun count(event: String): Int = recorded.count { it.first == event }

    /** Answers a plain 401 to the requests to [path] whose number, counting from 1, is in [refused]. */
    private fun refusing(
        path: String,
        vararg refused: Int,
    ): (String) -> Int? {
        var seen = 0
        return { requested -> if (requested == path && ++seen in refused) UNAUTHORIZED else null }
    }

    /** A ready session whose reader has since been found spent, so the next repair fetches config. */
    private suspend fun expired(fixture: SessionFixture) {
        fixture.seedRecord()
        fixture.coordinator.initialize()
        fixture.manager.invalidate()
        assertEquals(TapToPaySessionState.SessionExpired, fixture.state)
    }

    /** Answers a plain 401 to the first [refusals] requests to [path], and the script's answer after. */
    private class RefusingFirst(
        private val path: String,
        private val refusals: Int = 1,
    ) {
        private var seen = 0

        fun statusFor(requested: String): Int? = if (requested == path && seen++ < refusals) UNAUTHORIZED else null
    }

    /** Counts renewals and records the state each one found. */
    private class RecordingRenewal(
        private val stateNow: () -> TapToPaySessionState,
        private val failure: Exception? = null,
    ) {
        val statesSeen = mutableListOf<TapToPaySessionState>()

        suspend fun renew() {
            statesSeen += stateNow()
            failure?.let { throw it }
        }
    }

    private fun fixture(
        script: RouteScript,
        failure: Exception? = null,
    ): Pair<SessionFixture, RecordingRenewal> {
        lateinit var fixture: SessionFixture
        val renewal = RecordingRenewal({ fixture.state }, failure)
        fixture = SessionFixture(script, renewToken = renewal::renew)
        return fixture to renewal
    }

    /** The answers for a route: an empty refusal before [body] when [refused], [body] alone otherwise. */
    private fun refusedOnceIf(
        refused: Boolean,
        body: String,
    ): List<String> = if (refused) listOf("", body) else listOf(body)

    @Test
    fun `a refused token on config is renewed and the warm start reaches ready`() =
        runTest(timeout = TEST_TIMEOUT) {
            val refusing = RefusingFirst(RouteScript.CONFIG)
            val (fixture, renewal) =
                fixture(RouteScript(RouteScript.CONFIG to listOf("", configBody()), statusFor = refusing::statusFor))
            fixture.seedRecord()

            fixture.coordinator.initialize()

            assertEquals(TapToPaySessionState.Ready, fixture.state)
            assertEquals("one renewal", 1, renewal.statesSeen.size)
            assertEquals(listOf(RouteScript.CONFIG, RouteScript.CONFIG), fixture.routes)
        }

    @Test
    fun `a refused token on the first cold call is renewed and the run starts again from that call`() =
        runTest(timeout = TEST_TIMEOUT) {
            val refusing = RefusingFirst(RouteScript.CHALLENGE)
            val (fixture, renewal) =
                fixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf("", challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        statusFor = refusing::statusFor,
                    ),
                )

            fixture.coordinator.initialize()

            assertEquals(TapToPaySessionState.Ready, fixture.state)
            assertEquals(1, renewal.statesSeen.size)
            assertEquals(
                listOf(
                    RouteScript.CHALLENGE,
                    RouteScript.CHALLENGE,
                    RouteScript.REGISTER,
                    RouteScript.ATTEST,
                    RouteScript.CONFIG,
                ),
                fixture.routes,
            )
        }

    @Test
    fun `the first refusal lands nowhere before the renewal`() =
        runTest(timeout = TEST_TIMEOUT) {
            for (path in listOf(RouteScript.CHALLENGE, RouteScript.CONFIG)) {
                val refusing = RefusingFirst(path)
                val (fixture, renewal) =
                    fixture(
                        RouteScript(
                            RouteScript.CHALLENGE to refusedOnceIf(path == RouteScript.CHALLENGE, challengeBody()),
                            RouteScript.REGISTER to listOf(registerBody(status = "active")),
                            RouteScript.ATTEST to listOf(attestBody()),
                            RouteScript.CONFIG to refusedOnceIf(path == RouteScript.CONFIG, configBody()),
                            statusFor = refusing::statusFor,
                        ),
                    )

                fixture.coordinator.initialize()

                val seen = renewal.statesSeen.single()
                assertTrue("$path: the renewal found the session failed", seen !is TapToPaySessionState.Failed)
            }
        }

    @Test
    fun `a second refusal reports an expired token and is not renewed again`() =
        runTest(timeout = TEST_TIMEOUT) {
            val refusing = RefusingFirst(RouteScript.CONFIG, refusals = 2)
            val (fixture, renewal) =
                fixture(RouteScript(RouteScript.CONFIG to listOf("", ""), statusFor = refusing::statusFor))
            fixture.seedRecord()

            val failure = runCatching { fixture.coordinator.initialize() }.exceptionOrNull()

            assertEquals(PayabliErrorType.TOKEN_EXPIRED, failure?.let(TapToPayErrorCodes::typeFor))
            assertEquals(1, renewal.statesSeen.size)
            assertEquals(2, fixture.routes.size)
        }

    @Test
    fun `a failure that is not a refused token is not renewed`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (fixture, renewal) =
                fixture(RouteScript(RouteScript.CONFIG to listOf(""), statusFor = { 500 }))
            fixture.seedRecord()

            runCatching { fixture.coordinator.initialize() }

            assertEquals(0, renewal.statesSeen.size)
            assertEquals(1, fixture.routes.size)
        }

    @Test
    fun `a renewal that fails reaches the caller and nothing is sent again`() =
        runTest(timeout = TEST_TIMEOUT) {
            val providerFailed = PayabliGenericException(PayabliErrorType.TOKEN_PROVIDER_FAILED, "provider failed")
            val refusing = RefusingFirst(RouteScript.CONFIG)
            val (fixture, _) =
                fixture(
                    RouteScript(RouteScript.CONFIG to listOf("", configBody()), statusFor = refusing::statusFor),
                    failure = providerFailed,
                )
            fixture.seedRecord()

            val failure = runCatching { fixture.coordinator.initialize() }.exceptionOrNull()

            assertEquals(PayabliErrorType.TOKEN_PROVIDER_FAILED, failure?.let(TapToPayErrorCodes::typeFor))
            assertEquals(1, fixture.routes.size)
        }

    @Test
    fun `a refused token on a repair is renewed and the repair reaches ready`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (fixture, renewal) =
                fixture(
                    RouteScript(
                        RouteScript.CONFIG to listOf(configBody(), "", configBody()),
                        statusFor = refusing(RouteScript.CONFIG, 2),
                    ),
                )
            expired(fixture)

            fixture.coordinator.reinitializeIfNeeded()

            assertEquals(TapToPaySessionState.Ready, fixture.state)
            assertEquals(1, renewal.statesSeen.size)
            assertEquals(3, fixture.requestsTo(RouteScript.CONFIG).size)
        }

    @Test
    fun `a second refusal on a repair reports an expired token`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (fixture, renewal) =
                fixture(
                    RouteScript(
                        RouteScript.CONFIG to listOf(configBody(), "", ""),
                        statusFor = refusing(RouteScript.CONFIG, 2, 3),
                    ),
                )
            expired(fixture)

            val failure = runCatching { fixture.coordinator.reinitializeIfNeeded() }.exceptionOrNull()

            assertEquals(PayabliErrorType.TOKEN_EXPIRED, failure?.let(TapToPayErrorCodes::typeFor))
            assertEquals(1, renewal.statesSeen.size)
            assertEquals(3, fixture.requestsTo(RouteScript.CONFIG).size)
        }

    @Test
    fun `a renewed initialize reports one start and one outcome, and each attestation attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (fixture, _) =
                fixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf("", challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        statusFor = refusing(RouteScript.CHALLENGE, 1),
                    ),
                )

            fixture.coordinator.initialize()

            assertEquals(1, count(TelemetryEvents.TTP_INITIALIZE_STARTED))
            assertEquals(1, count(TelemetryEvents.TTP_INITIALIZE_SUCCEEDED))
            assertEquals(0, count(TelemetryEvents.TTP_INITIALIZE_FAILED))
            assertEquals(2, count(TelemetryEvents.TTP_ATTESTATION_STARTED))
            assertEquals(1, count(TelemetryEvents.TTP_ATTESTATION_SUCCEEDED))
            val failed = recorded.single { it.first == TelemetryEvents.TTP_ATTESTATION_FAILED }.second
            assertEquals(PayabliErrorType.TOKEN_EXPIRED.code.toString(), failed[TelemetryProperty.ERROR_NUMBER.key])
        }

    @Test
    fun `a renewed repair reports one start and one outcome`() =
        runTest(timeout = TEST_TIMEOUT) {
            val (fixture, _) =
                fixture(
                    RouteScript(
                        RouteScript.CONFIG to listOf(configBody(), "", configBody()),
                        statusFor = refusing(RouteScript.CONFIG, 2),
                    ),
                )
            expired(fixture)

            fixture.coordinator.reinitializeIfNeeded()

            assertEquals(1, count(TelemetryEvents.TTP_REINITIALIZE_STARTED))
            assertEquals(1, count(TelemetryEvents.TTP_REINITIALIZE_SUCCEEDED))
        }

    @Test
    fun `a refused token on activation is renewed and the code is sent once more`() =
        runTest(timeout = TEST_TIMEOUT) {
            val refusing = RefusingFirst(RouteScript.ACTIVATE)
            val (fixture, renewal) =
                fixture(
                    RouteScript(
                        RouteScript.CONFIG to listOf(decline(403, NOT_ACTIVE)),
                        RouteScript.ACTIVATE to listOf("", activateBody()),
                        statusFor = refusing::statusFor,
                    ),
                )
            fixture.seedRecord()
            runCatching { fixture.coordinator.initialize() }
            assertEquals(TapToPaySessionState.PendingActivation(DEVICE_ID), fixture.state)

            fixture.coordinator.activateDevice("123456")

            assertEquals(TapToPaySessionState.Idle, fixture.state)
            assertEquals(1, renewal.statesSeen.size)
            assertEquals(2, fixture.requestsTo(RouteScript.ACTIVATE).size)
        }

    @Test
    fun `a second refusal on activation reports an expired token and leaves the session waiting`() =
        runTest(timeout = TEST_TIMEOUT) {
            val refusing = RefusingFirst(RouteScript.ACTIVATE, refusals = 2)
            val (fixture, renewal) =
                fixture(
                    RouteScript(
                        RouteScript.CONFIG to listOf(decline(403, NOT_ACTIVE)),
                        RouteScript.ACTIVATE to listOf("", ""),
                        statusFor = refusing::statusFor,
                    ),
                )
            fixture.seedRecord()
            runCatching { fixture.coordinator.initialize() }

            val failure = runCatching { fixture.coordinator.activateDevice("123456") }.exceptionOrNull()

            assertEquals(PayabliErrorType.TOKEN_EXPIRED, failure?.let(TapToPayErrorCodes::typeFor))
            assertEquals(TapToPaySessionState.PendingActivation(DEVICE_ID), fixture.state)
            assertEquals(1, renewal.statesSeen.size)
        }
}
