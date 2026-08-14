package com.payabli.sdk.taptopay.enrollment

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * What each refusal from `/activate` becomes, and which of them are allowed to discard the record.
 *
 * The discarding half is the point. The service answers two unrelated conditions under one result code, and
 * only one of them is a reason to forget the device.
 */
class ActivationFailurePolicyTest {
    /** One row per outcome the service can return, with what the caller sees and whether the record survives. */
    private data class Case(
        val resultCode: Int,
        val reason: String,
        val expected: Class<out DeviceActivationException>,
        val discards: Boolean,
    )

    private val cases =
        listOf(
            Case(400, "Invalid activation code.", DeviceActivationException.CodeIncorrect::class.java, false),
            Case(
                400,
                "Activation code has expired. Request a new challenge.",
                DeviceActivationException.CodeExpired::class.java,
                false,
            ),
            Case(
                400,
                "Too many failed activation attempts. Request a new challenge.",
                DeviceActivationException.AttemptsExhausted::class.java,
                false,
            ),
            Case(
                400,
                "No active challenge for this device.",
                DeviceActivationException.CodeNotIssued::class.java,
                false,
            ),
            Case(
                400,
                "Stored activation code is invalid.",
                DeviceActivationException.CodeUnreadable::class.java,
                false,
            ),
            Case(
                400,
                "Device is not pending activation.",
                DeviceActivationException.DeviceNotPending::class.java,
                false,
            ),
            Case(
                400,
                "Assertion verification failed: timestamp outside the accepted window",
                DeviceActivationException.AssertionRejected::class.java,
                false,
            ),
            Case(
                400,
                "X-App-Assertion header is required.",
                DeviceActivationException.RequestRejected::class.java,
                false,
            ),
            Case(
                401,
                "Device not attested or attestation revoked.",
                DeviceActivationException.AttestationRevoked::class.java,
                true,
            ),
            Case(
                401,
                "Not authorized for this entry point.",
                DeviceActivationException.EntryNotAuthorized::class.java,
                false,
            ),
            Case(404, "Device not found.", DeviceActivationException.DeviceUnknown::class.java, true),
            Case(
                404,
                "Paypoint 'entry-point-value' not found.",
                DeviceActivationException.PaypointUnknown::class.java,
                false,
            ),
            Case(500, "Internal server error.", DeviceActivationException.ServiceFailed::class.java, false),
            Case(418, "something nobody has written down", DeviceActivationException.Unclassified::class.java, false),
        )

    @Test
    fun `every refusal gets its own disposition, and exactly two of them discard the record`() =
        runTest(timeout = TEST_TIMEOUT) {
            for (case in cases) {
                val fixture =
                    EnrollmentFixture(
                        RouteScript(RouteScript.ACTIVATE to listOf(decline(case.resultCode, case.reason))),
                    )
                fixture.seedRecord()

                val thrown =
                    runCatching { fixture.enrollment.confirmActivation(ACTIVATION_CODE) }.exceptionOrNull()

                assertEquals(case.reason, case.expected, thrown?.javaClass)
                if (case.discards) {
                    assertNull(case.reason, fixture.storedRecord())
                } else {
                    assertNotNull(case.reason, fixture.storedRecord())
                }
                // Whatever the outcome, the key is never spent over it.
                assertEquals(case.reason, 0, fixture.deviceKey.deletions)
            }
        }

    @Test
    fun `the two meanings of result code 401 are told apart, and only one discards`() =
        runTest(timeout = TEST_TIMEOUT) {
            val revoked =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.ACTIVATE to listOf(decline(401, "Device not attested or attestation revoked.")),
                    ),
                )
            revoked.seedRecord()
            runCatching { revoked.enrollment.confirmActivation(ACTIVATION_CODE) }
            assertNull("a revoked attestation discards the record", revoked.storedRecord())

            val unauthorized =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.ACTIVATE to listOf(decline(401, "Not authorized for this entry point.")),
                    ),
                )
            unauthorized.seedRecord()
            runCatching { unauthorized.enrollment.confirmActivation(ACTIVATION_CODE) }
            // Discarding here would destroy a working enrolment over a token that is merely scoped wrong.
            assertNotNull("a permission problem discards nothing", unauthorized.storedRecord())
        }

    @Test
    fun `an unrecognized reason under a discarding code still discards nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                EnrollmentFixture(
                    RouteScript(RouteScript.ACTIVATE to listOf(decline(401, "some future wording"))),
                )
            fixture.seedRecord()

            runCatching { fixture.enrollment.confirmActivation(ACTIVATION_CODE) }

            // The destructive branch needs a positive match, so a service that rewords stops discarding
            // and never starts discarding the wrong things.
            assertNotNull(fixture.storedRecord())
            val record = fixture.logger.records.single { it.message.contains("unrecognized reason") }
            assertEquals(listOf("event", "errorCode"), record.fieldNames)
        }

    @Test
    fun `a wrong code leaves the record so the remaining attempts are reachable`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.ACTIVATE to
                            listOf(decline(400, "Invalid activation code."), activateBody()),
                    ),
                )
            fixture.seedRecord()

            runCatching { fixture.enrollment.confirmActivation("000000") }
            fixture.enrollment.confirmActivation(ACTIVATION_CODE)

            // The binding is untouched by activation: it records what was attested, not what the service
            // did afterwards.
            assertNotNull(fixture.storedRecord())
        }

    @Test
    fun `a code that is not six digits is refused before anything is sent`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(RouteScript())
            fixture.seedRecord()

            for (bad in listOf("12345", "1234567", "12345a", "", " 12345")) {
                val thrown = runCatching { fixture.enrollment.confirmActivation(bad) }.exceptionOrNull()
                assertEquals(bad, DeviceActivationException.CodeMalformed::class.java, thrown?.javaClass)
            }
            // The service counts a wrong code against a five-attempt lockout; a typo must not spend one.
            assertTrue(fixture.transport.requests.isEmpty())
        }

    @Test
    fun `a record from another paypoint is not activated, and is not discarded`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(RouteScript())
            fixture.seedRecord(entry = "a-different-entry-point")

            val thrown = runCatching { fixture.enrollment.confirmActivation(ACTIVATION_CODE) }.exceptionOrNull()

            assertEquals(DeviceActivationException.NotEnrolled::class.java, thrown?.javaClass)
            assertTrue(fixture.transport.requests.isEmpty())
            // Sending it would be answered as an unknown device, and that classification discards the
            // record — which belongs to a paypoint this session is not talking to.
            assertNotNull(fixture.storedRecord())
        }

    @Test
    fun `activating with nothing enrolled sends nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(RouteScript())

            val thrown = runCatching { fixture.enrollment.confirmActivation(ACTIVATION_CODE) }.exceptionOrNull()

            assertEquals(DeviceActivationException.NotEnrolled::class.java, thrown?.javaClass)
            assertTrue(fixture.transport.requests.isEmpty())
        }

    @Test
    fun `a successful activation records it`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(RouteScript(RouteScript.ACTIVATE to listOf(activateBody())))
            fixture.seedRecord()

            fixture.enrollment.confirmActivation(ACTIVATION_CODE)

            // Nothing is written on success, so there is no post-success write that can fail and leave a
            // record disagreeing with the service.
            assertEquals(listOf("get:$RECORD_ENTRY", RouteScript.ACTIVATE), fixture.trace)
        }

    @Test
    fun `the assertion headers are attached to the activation`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(RouteScript(RouteScript.ACTIVATE to listOf(activateBody())))
            fixture.seedRecord()

            fixture.enrollment.confirmActivation(ACTIVATION_CODE)

            val headers = fixture.transport.request.headers
            for (name in listOf("X-App-Assertion", "X-App-KeyId", "X-Device-Id", "X-Assertion-Timestamp")) {
                if (headers[name].isNullOrBlank()) fail("$name is missing from the activation")
            }
        }

    @Test
    fun `no exception in this taxonomy prints the service's wording`() {
        for (case in cases) {
            val thrown = DeviceActivationFailures().map(case.resultCode, case.reason)
            assertTrue(case.reason, thrown.toString().contains("resultCode=${case.resultCode}"))
            assertTrue(case.reason, !thrown.toString().contains(case.reason))
        }
    }
}
