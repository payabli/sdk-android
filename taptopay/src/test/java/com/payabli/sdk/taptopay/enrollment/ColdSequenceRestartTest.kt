package com.payabli.sdk.taptopay.enrollment

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * The claim the coordinator makes about itself: nothing inside the sequence is retried, because the whole
 * sequence is what is safe to repeat.
 *
 * Each test here interrupts it at a different call and runs it again from the top.
 */
class ColdSequenceRestartTest {
    @Test
    fun `a run interrupted at register resumes without registering a second device`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody(), challengeBody()),
                        RouteScript.REGISTER to listOf(decline(500, "Internal server error."), registerBody()),
                        RouteScript.ATTEST to listOf(attestBody()),
                    ),
                )

            runCatching { fixture.enrollment.enroll() }
            // Nothing was kept from the failed attempt, so the second run is a clean cold start.
            assertNull(fixture.storedRecord())

            val outcome = fixture.enrollment.enroll()

            assertTrue((outcome as EnrollmentOutcome.Attested).activationRequired)
            assertEquals(DEVICE_ID, fixture.storedRecord()!!.deviceId)
        }

    @Test
    fun `a run interrupted at attest presents the same key on the next attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody(), challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(), registerBody()),
                        RouteScript.ATTEST to
                            listOf(decline(400, "Challenge not found or already consumed."), attestBody()),
                    ),
                )

            runCatching { fixture.enrollment.enroll() }
            fixture.enrollment.enroll()

            val keyIds =
                fixture.transport.requests
                    .filter { it.path == RouteScript.ATTEST }
                    .map { it.body!!.toString(Charsets.UTF_8) }
            assertEquals(2, keyIds.size)
            // The key sits at one fixed handle, so no attempt strands one or mints a second.
            assertTrue(keyIds.all { it.contains(""""keyId":"${FakeDeviceKey.KEY_IDENTITY}"""") })
            assertEquals(0, fixture.deviceKey.deletions)
        }

    @Test
    fun `a fresh challenge is taken on every restart`() =
        runTest(timeout = TEST_TIMEOUT) {
            val second = "YS1zZWNvbmQtY2hhbGxlbmdlLXZhbHVlLWhlcmU="
            val fixture =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to
                            listOf(
                                challengeBody(),
                                successEnvelopeChallenge(second),
                            ),
                        RouteScript.REGISTER to listOf(registerBody(), registerBody()),
                        RouteScript.ATTEST to listOf(decline(500, "Internal server error."), attestBody()),
                    ),
                )

            runCatching { fixture.enrollment.enroll() }
            fixture.enrollment.enroll()

            // `/attest` consumes its challenge with a delete-on-read, so a restart that reused one would be
            // attesting against a value the service has already retired.
            val used = fixture.attestor.challenges.map { it.value }
            assertEquals(2, used.size)
            assertNotEquals(used[0], used[1])
        }

    @Test
    fun `enrolling twice in a row makes no second round trip`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody()),
                        RouteScript.ATTEST to listOf(attestBody()),
                    ),
                )

            fixture.enrollment.enroll()
            val second = fixture.enrollment.enroll()

            // The second call is answered from the stored binding. Without that, `/register` would retire an
            // active device and cost the merchant a fresh code.
            assertEquals(EnrollmentOutcome.AlreadyAttested, second)
            assertEquals(3, fixture.routes.size)
        }
}

private fun successEnvelopeChallenge(challenge: String): String =
    com.payabli.sdk.taptopay.attestation.device.successEnvelope(
        """{"challengeId":"$CHALLENGE_ID","challenge":"$challenge"}""",
    )
