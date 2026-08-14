package com.payabli.sdk.taptopay.enrollment.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.taptopay.attestation.device.DeviceAssertionSigner
import com.payabli.sdk.taptopay.enrollment.AttestedDevice
import com.payabli.sdk.taptopay.enrollment.AttestedDeviceStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 60.seconds

/**
 * The record against the real encrypted store, and the assertion against the real key.
 *
 * Everything here is unfalsifiable off a device. The unit tier's store keeps plaintext in a map by design,
 * so "the record is not readable in the file" can only be asked here; and the signature can only be checked
 * against a key the platform actually generated.
 */
@RunWith(AndroidJUnit4::class)
class DeviceRecordInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storeFile get() = File(context.noBackupFilesDir, "payabli-secure-store.json")

    @Before
    @After
    fun clean() =
        runTest(timeout = TEST_TIMEOUT) {
            val trust = DeviceTrust.open(context)
            trust.store.remove(RECORD_ENTRY)
            trust.store.remove(FOREIGN_ENTRY)
        }

    @Test
    fun aRecordWrittenThroughTheAccessorSurvivesAFreshOpen() =
        runTest(timeout = TEST_TIMEOUT) {
            AttestedDeviceStore(DeviceTrust.open(context).store)
                .write(AttestedDevice(ENTRY, DEVICE_ID, KEY_ID))

            val read = AttestedDeviceStore(DeviceTrust.open(context).store).read()

            assertNotNull(read)
            assertEquals(DEVICE_ID, read!!.deviceId)
            assertEquals(KEY_ID, read.keyId)
        }

    @Test
    fun theRecordIsNotReadableAsPlaintextInTheFile() =
        runTest(timeout = TEST_TIMEOUT) {
            AttestedDeviceStore(DeviceTrust.open(context).store)
                .write(AttestedDevice(ENTRY, DEVICE_ID, KEY_ID))

            val contents = storeFile.readText()

            // The key name is plaintext by design and may be logged; the value must not be.
            assertTrue(contents.contains(RECORD_ENTRY))
            assertFalse(contents.contains(DEVICE_ID))
            assertFalse(contents.contains(KEY_ID))
            assertFalse(contents.contains(ENTRY))
        }

    @Test
    fun clearingTheRecordLeavesAnotherConsumersEntryIntact() =
        runTest(timeout = TEST_TIMEOUT) {
            val trust = DeviceTrust.open(context)
            trust.store.set(FOREIGN_ENTRY, FOREIGN_VALUE.encodeToByteArray())
            val store = AttestedDeviceStore(trust.store)
            store.write(AttestedDevice(ENTRY, DEVICE_ID, KEY_ID))

            store.clear()

            // The store is shared, which is the whole reason removal is per entry and there is no bulk
            // clear on the accessor. This is the test that defends that decision.
            assertNull(store.read())
            assertArrayEquals(FOREIGN_VALUE.encodeToByteArray(), trust.store.get(FOREIGN_ENTRY))
        }

    @Test
    fun theAssertionVerifiesAgainstThePointTheKeyReports() =
        runTest(timeout = TEST_TIMEOUT) {
            val trust = DeviceTrust.open(context)
            val published = trust.key.publicKey()

            val assertion = DeviceAssertionSigner(trust.key).sign(DEVICE_ID)

            // Cheap here, and it catches an encoding regression before a live run spends a challenge on it.
            val point = published.point
            val half = (point.size - 1) / 2
            val x = BigInteger(1, point.copyOfRange(1, 1 + half))
            val y = BigInteger(1, point.copyOfRange(1 + half, point.size))
            val parameters =
                AlgorithmParameters
                    .getInstance("EC")
                    .apply {
                        init(ECGenParameterSpec("secp256r1"))
                    }.getParameterSpec(ECParameterSpec::class.java)
            val publicKey =
                KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), parameters))

            val verifier =
                Signature.getInstance("SHA256withECDSA").apply {
                    initVerify(publicKey)
                    update(MessageDigest.getInstance("SHA-256").digest(assertion.timestamp.toByteArray(Charsets.UTF_8)))
                }
            assertTrue(
                verifier.verify(
                    java.util.Base64
                        .getDecoder()
                        .decode(assertion.assertion),
                ),
            )
            assertEquals(published.identity, assertion.keyId)
        }

    private companion object {
        const val RECORD_ENTRY = "com.payabli.sdk.taptopay.device.v1"
        const val FOREIGN_ENTRY = "com.payabli.sdk.core.devicerecord.test.foreign"
        const val FOREIGN_VALUE = "another consumer's value"
        const val ENTRY = "entry-point-value"
        const val DEVICE_ID = "device-id-value"
        const val KEY_ID = "key-identity-value"
    }
}
