package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.taptopay.attestation.VerdictClass
import com.payabli.sdk.taptopay.attestation.device.successEnvelope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/** The cold sequence, and the check that decides whether it runs at all. */
class DeviceEnrollmentTest {
    private fun coldScript() =
        RouteScript(
            RouteScript.CHALLENGE to listOf(challengeBody()),
            RouteScript.REGISTER to listOf(registerBody()),
            RouteScript.ATTEST to listOf(attestBody()),
        )

    @Test
    fun `the cold sequence calls challenge, register and attest in that order`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(coldScript())

            fixture.enrollment.enroll()

            assertEquals(
                listOf(RouteScript.CHALLENGE, RouteScript.REGISTER, RouteScript.ATTEST),
                fixture.routes,
            )
        }

    @Test
    fun `nothing is written until the attestation has been accepted`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(coldScript())

            fixture.enrollment.enroll()

            // One list across both fakes: the property is that the write comes after the last call, and a
            // per-fake list cannot express an ordering between two of them.
            assertEquals(
                listOf(
                    "get:$RECORD_ENTRY",
                    "get:$LEGACY_RECORD_ENTRY",
                    RouteScript.CHALLENGE,
                    RouteScript.REGISTER,
                    RouteScript.ATTEST,
                    "get:$RECORD_ENTRY",
                    "get:$LEGACY_RECORD_ENTRY",
                    "set:$RECORD_ENTRY",
                ),
                fixture.trace,
            )
        }

    @Test
    fun `the attestation is taken over the nonce derived from the challenge the service issued`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(coldScript())

            fixture.enrollment.enroll()

            val expected =
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(SERVER_CHALLENGE)),
                )
            val challenge = fixture.attestor.challenges.single()
            assertEquals(VerdictClass.CLASSIC, challenge.verdictClass)
            assertEquals(expected, challenge.value)
        }

    @Test
    fun `register and attest name the same key, and the key is read once`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(coldScript())

            fixture.enrollment.enroll()

            val bodies = fixture.transport.requests.associate { it.path to it.body?.toString(Charsets.UTF_8).orEmpty() }
            assertTrue(bodies.getValue(RouteScript.REGISTER).contains(""""keyId":"${FakeDeviceKey.KEY_IDENTITY}""""))
            assertTrue(bodies.getValue(RouteScript.ATTEST).contains(""""keyId":"${FakeDeviceKey.KEY_IDENTITY}""""))
            // One read, so the point and the thumbprint cannot come from two different keys.
            assertEquals(1, fixture.deviceKey.publicKeyReads)
        }

    @Test
    fun `the public key is sent as base64 of the uncompressed point`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(coldScript())

            fixture.enrollment.enroll()

            val attest = fixture.transport.requests.first { it.path == RouteScript.ATTEST }
            val expected = Base64.getEncoder().encodeToString(FakeDeviceKey.POINT)
            assertTrue(attest.body!!.toString(Charsets.UTF_8).contains(""""publicKey":"$expected""""))
        }

    @Test
    fun `a pending registration is returned as a flag, not thrown`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(coldScript())

            val outcome = fixture.enrollment.enroll()

            assertTrue((outcome as EnrollmentOutcome.Attested).activationRequired)
            assertNotNull(fixture.storedRecord())
        }

    @Test
    fun `a registration that is already active owes no activation`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                    ),
                )

            val outcome = fixture.enrollment.enroll()

            assertFalse((outcome as EnrollmentOutcome.Attested).activationRequired)
            assertNotNull(fixture.storedRecord())
        }

    @Test
    fun `a registration with no status still owes activation`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        // The field is nullable on the wire, so this is a shape the client must survive.
                        RouteScript.REGISTER to listOf(successEnvelope("""{"deviceId":"$DEVICE_ID"}""")),
                        RouteScript.ATTEST to listOf(attestBody()),
                    ),
                )

            val outcome = fixture.enrollment.enroll()

            // Reporting it as active is the direction a caller cannot recover from: it stops asking for
            // a code the device still owes.
            assertTrue((outcome as EnrollmentOutcome.Attested).activationRequired)
        }

    @Test
    fun `a registration with an unrecognized status still owes activation`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "superseded")),
                        RouteScript.ATTEST to listOf(attestBody()),
                    ),
                )

            assertTrue((fixture.enrollment.enroll() as EnrollmentOutcome.Attested).activationRequired)
        }

    @Test
    fun `a device that is already attested makes no request, and claims nothing about activation`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(RouteScript())
            fixture.seedRecord()

            val outcome = fixture.enrollment.enroll()

            // Nothing was asked, so there is nothing to report. The sibling SDK answers this from a live
            // call for the same reason: a remembered activation state is one nobody re-checks.
            assertEquals(EnrollmentOutcome.AlreadyAttested, outcome)
            assertTrue(fixture.transport.requests.isEmpty())
        }

    @Test
    fun `a record naming a key the store no longer holds is not treated as enrolled`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(coldScript())
            fixture.seedRecord(keyId = "a-thumbprint-from-a-key-that-is-gone")

            fixture.enrollment.enroll()

            // Decided locally. The service would have answered this as a revoked attestation, which means
            // something else and would be read that way in an incident.
            assertEquals(
                listOf(RouteScript.CHALLENGE, RouteScript.REGISTER, RouteScript.ATTEST),
                fixture.routes,
            )
            assertEquals(FakeDeviceKey.KEY_IDENTITY, fixture.storedRecord()!!.keyId)
        }

    @Test
    fun `a record made against another paypoint is not treated as enrolled`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(coldScript())
            fixture.seedRecord(entry = "a-different-entry-point")

            fixture.enrollment.enroll()

            assertEquals(3, fixture.routes.size)
            assertEquals(ENTRY, fixture.storedRecord()!!.entry)
        }

    @Test
    fun `a binding made against another paypoint survives an enrollment that fails`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(decline(500, "Internal server error.")),
                    ),
                )
            fixture.seedRecord(entry = "a-different-entry-point")

            runCatching { fixture.enrollment.enroll() }

            // The other paypoint's binding is still live at the service. Discarding it here would send that
            // paypoint's next enrollment through the cold sequence, and registering retires an active
            // device and costs the merchant a fresh code.
            assertNotNull(fixture.storedRecord("a-different-entry-point"))
            assertEquals(
                listOf("get:$RECORD_ENTRY", "remove:$LEGACY_RECORD_ENTRY"),
                fixture.storage.operations,
            )
        }

    @Test
    fun `a binding made against another paypoint is kept when this one is attested`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(coldScript())
            fixture.seedRecord(entry = "a-different-entry-point")

            fixture.enrollment.enroll()

            // Reads and one write. No remove, so nothing is unstored between them.
            assertEquals(
                listOf(
                    "get:$RECORD_ENTRY",
                    "remove:$LEGACY_RECORD_ENTRY",
                    "get:$RECORD_ENTRY",
                    "set:$RECORD_ENTRY",
                ),
                fixture.storage.operations,
            )
            // Both bindings are held: this one is newly attested, the other one is untouched.
            assertNotNull(fixture.storedRecord())
            assertNotNull(fixture.storedRecord("a-different-entry-point"))
        }

    @Test
    fun `resetting leaves another paypoint's binding alone`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(RouteScript())
            fixture.seedRecord(entry = "a-different-entry-point")

            fixture.enrollment.reset()

            // Reset forgets this paypoint. The other one's binding is live, and removing it would send its
            // next enrollment through a registration that retires an active device.
            assertNotNull(fixture.storedRecord("a-different-entry-point"))
            assertEquals(
                listOf("get:$RECORD_ENTRY", "remove:$LEGACY_RECORD_ENTRY"),
                fixture.storage.operations,
            )
        }

    @Test
    fun `being told the row was created reports nothing when the binding was another paypoint's`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(outcome = "created")),
                        RouteScript.ATTEST to listOf(attestBody()),
                    ),
                )
            fixture.seedRecord(entry = "a-different-entry-point")

            fixture.enrollment.enroll()

            // A paypoint that has never seen this device answers `created`. That is the expected answer.
            assertTrue(fixture.logger.records.none { it.message.contains("did not recognize") })
        }

    @Test
    fun `a response carrying no outcome decodes and reports nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(coldScript())
            fixture.seedRecord(keyId = "a-thumbprint-from-a-key-that-is-gone")

            fixture.enrollment.enroll()

            assertTrue(fixture.logger.records.none { it.message.contains("did not recognize") })
        }

    @Test
    fun `holding a record and being told the row was created reports the replacement`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(outcome = "created")),
                        RouteScript.ATTEST to listOf(attestBody()),
                    ),
                )
            fixture.seedRecord(keyId = "a-thumbprint-from-a-key-that-is-gone")

            fixture.enrollment.enroll()

            val record = fixture.logger.records.single { it.message.contains("did not recognize") }
            assertEquals(LogLevel.WARN, record.level)
            assertEquals(listOf("event", "outcome"), record.fieldNames)
        }

    @Test
    fun `being told the row was reused reports nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                EnrollmentFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(outcome = "reused")),
                        RouteScript.ATTEST to listOf(attestBody()),
                    ),
                )
            fixture.seedRecord(keyId = "a-thumbprint-from-a-key-that-is-gone")

            fixture.enrollment.enroll()

            assertTrue(fixture.logger.records.none { it.message.contains("did not recognize") })
        }

    @Test
    fun `no log field carries the challenge, the device handle or the attestation token`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(coldScript())

            fixture.enrollment.enroll()

            for (record in fixture.logger.records) {
                assertFalse(record.message.contains(SERVER_CHALLENGE))
                assertFalse(record.message.contains(DEVICE_ID))
                assertFalse(record.message.contains(FakeAppAttestor.TOKEN))
                assertFalse(record.fieldNames.contains("deviceId"))
            }
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `the production logger is the default, and constructing with it works`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The one exercise of the defaulted argument. An uncovered default is one nobody has run.
            val fixture = EnrollmentFixture(coldScript())
            val production =
                DeviceEnrollment(
                    entry = ENTRY,
                    appId = APP_ID,
                    client =
                        com.payabli.sdk.taptopay.attestation.device
                            .DeviceServiceClient(fixture.transport),
                    attestor = fixture.attestor,
                    deviceKey = fixture.deviceKey,
                    signer =
                        com.payabli.sdk.taptopay.attestation.device.DeviceAssertionSigner(
                            fixture.deviceKey,
                            EnrollmentFixture.FIXED_CLOCK,
                        ),
                    store = fixture.store,
                    description = DeviceDescription(HARDWARE_ID, null, MODEL, OS_VERSION),
                    dispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(),
                )

            assertNotNull(production.enroll())
        }
}
