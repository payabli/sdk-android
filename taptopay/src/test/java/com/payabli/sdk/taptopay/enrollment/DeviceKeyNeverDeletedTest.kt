package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.taptopay.attestation.AttestationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

/**
 * The device key survives every outcome either entry point can reach.
 *
 * Asserted, not assumed, so the table below is the artifact under review:
 * the claim is only ever as strong as the set of failures it walks. Every refusal `/attest` can return is
 * about the application, the paypoint, the device's state or the challenge; none of them says the key was
 * rejected, and discarding a usable key over a configuration problem is a trade nobody wants.
 *
 * [FakeDeviceKey.delete] both counts and throws, so a deletion is loud where it happens and still visible in
 * the count if some future `runCatching` were to swallow the throw.
 */
class DeviceKeyNeverDeletedTest {
    private fun script(
        challenge: String? = challengeBody(),
        register: String? = registerBody(),
        attest: String? = attestBody(),
    ) = RouteScript(
        *listOfNotNull(
            challenge?.let { RouteScript.CHALLENGE to listOf(it) },
            register?.let { RouteScript.REGISTER to listOf(it) },
            attest?.let { RouteScript.ATTEST to listOf(it) },
        ).toTypedArray(),
    )

    @Test
    fun `no refusal of the cold sequence deletes the key`() =
        runTest(timeout = TEST_TIMEOUT) {
            val refusals =
                listOf(
                    "challenge 500" to script(challenge = decline(500, "Internal server error.")),
                    "register 400" to script(register = decline(400, "hardwareId is required in the request body.")),
                    "register 404" to script(register = decline(404, "Paypoint 'entry-point-value' not found.")),
                    "attest 400 challenge" to script(attest = decline(400, "Challenge not found or already consumed.")),
                    "attest 400 public key" to
                        script(attest = decline(400, "PublicKey is required for Android attestation.")),
                    "attest 403 unrecognized" to
                        script(attest = decline(403, "Application is not recognized by Google Play.")),
                    "attest 403 undetermined" to
                        script(attest = decline(403, "Application identity could not be determined.")),
                    "attest 403 unauthorized" to
                        script(attest = decline(403, "Application is not authorized for this paypoint.")),
                    "attest 403 not attestable" to
                        script(attest = decline(403, "Device is not in a state that allows attestation.")),
                    "attest 404" to script(attest = decline(404, "Device not found.")),
                    "attest 401" to script(attest = decline(401, "Not authorized for this entry point.")),
                    "attest 500" to script(attest = decline(500, "Internal server error.")),
                )

            for ((name, routes) in refusals) {
                val fixture = EnrollmentFixture(routes)
                runCatching { fixture.enrollment.enroll() }
                assertEquals(name, 0, fixture.deviceKey.deletions)
            }
        }

    @Test
    fun `no attestation failure deletes the key`() =
        runTest(timeout = TEST_TIMEOUT) {
            val failures =
                listOf<AttestationException>(
                    AttestationException.Retryable(errorCode = -1),
                    AttestationException.Throttled(errorCode = -8),
                    AttestationException.RemediationRequired(errorCode = -14),
                    AttestationException.IntegrityFailed(errorCode = -6),
                    AttestationException.Misconfigured(errorCode = -13),
                    AttestationException.ChallengeReused(),
                )

            for (failure in failures) {
                val fixture =
                    EnrollmentFixture(script(), attestor = FakeAppAttestor(failure = failure))
                runCatching { fixture.enrollment.enroll() }
                assertEquals(failure.javaClass.simpleName, 0, fixture.deviceKey.deletions)
                // And nothing was recorded, because the attestation never happened.
                assertNull(failure.javaClass.simpleName, fixture.storedRecord())
            }
        }

    @Test
    fun `no key store failure deletes the key`() =
        runTest(timeout = TEST_TIMEOUT) {
            val failures =
                listOf<DeviceKeyException>(
                    DeviceKeyException.KeyLost(),
                    DeviceKeyException.SigningFailed(),
                    DeviceKeyException.CryptoUnavailable(),
                )

            for (failure in failures) {
                val reading = EnrollmentFixture(script(), deviceKey = FakeDeviceKey(publicKeyFailure = failure))
                runCatching { reading.enrollment.enroll() }
                assertEquals(failure.javaClass.simpleName, 0, reading.deviceKey.deletions)

                val signing =
                    EnrollmentFixture(
                        RouteScript(RouteScript.ACTIVATE to listOf(activateBody())),
                        deviceKey = FakeDeviceKey(signFailure = failure),
                    )
                signing.seedRecord()
                runCatching { signing.enrollment.activateDevice(ACTIVATION_CODE) }
                assertEquals(failure.javaClass.simpleName, 0, signing.deviceKey.deletions)
            }
        }

    @Test
    fun `no storage failure deletes the key`() =
        runTest(timeout = TEST_TIMEOUT) {
            val failures =
                listOf<SecureStorageException>(
                    SecureStorageException.KeyInvalidated(),
                    SecureStorageException.ValueUnreadable(),
                    SecureStorageException.CryptoUnavailable(),
                    SecureStorageException.StorageUnavailable(),
                )

            for (failure in failures) {
                for (operation in listOf("get", "set")) {
                    val fixture =
                        EnrollmentFixture(
                            script(),
                            storeFailure = { op, _ -> if (op == operation) failure else null },
                        )
                    runCatching { fixture.enrollment.enroll() }
                    assertEquals(
                        "${failure.javaClass.simpleName} on $operation",
                        0,
                        fixture.deviceKey.deletions,
                    )
                }
            }
        }

    @Test
    fun `a key lost while signing discards the record but never the key`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                EnrollmentFixture(
                    RouteScript(),
                    deviceKey = FakeDeviceKey(signFailure = DeviceKeyException.KeyLost()),
                )
            fixture.seedRecord()

            runCatching { fixture.enrollment.activateDevice(ACTIVATION_CODE) }

            // The key store has already discarded it, so the record names something that cannot exist.
            assertNull(fixture.storedRecord())
            assertEquals(0, fixture.deviceKey.deletions)
        }

    @Test
    fun `resetting forgets the device and leaves the key`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = EnrollmentFixture(RouteScript())
            fixture.seedRecord()

            fixture.enrollment.reset()

            assertNull(fixture.storedRecord())
            assertEquals(0, fixture.deviceKey.deletions)
        }
}
