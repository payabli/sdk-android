package com.payabli.sdk.core.devicekey.platform

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.ManualDeviceTest
import com.payabli.sdk.core.devicekey.impl.DeviceKeyHandle
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import kotlin.time.Duration.Companion.seconds

/**
 * What only real hardware can answer about the device key. **Excluded from CI**; see [ManualDeviceTest].
 *
 * The emulator suite covers the authorizations, the point, signing, the lost-key paths, rediscovery and the
 * StrongBox fallback. None of that is repeated. What is left needs a secure element, which an emulator answers
 * `SOFTWARE` to however correct the code is.
 *
 * **One question per test.** Whether the key is in secure hardware at all, whether it reached the best level
 * the device offers, and whether a signature verifiably came from a hardware-backed key are three failures
 * with three causes. A single test branching on device capability runs half of itself on any given phone, and
 * the half that did not run reads as covered.
 *
 * Run it against every wired phone, not one: vendors differ in what they advertise.
 * ```
 * ANDROID_SERIAL=<serial> ./gradlew :core:connectedAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.annotation=com.payabli.sdk.core.ManualDeviceTest
 * ```
 *
 * The capability signal is API-aware for the reason the storage tier documents: `FEATURE_HARDWARE_KEYSTORE`
 * arrived at API 31, so below it the signal is `FEATURE_FINGERPRINT` and the basis is the Compatibility
 * Definition Document clause quoted on [requiresHardwareBackedKeystore].
 *
 * **Not covered: `KeyPermanentlyInvalidatedException`.** This key omits `setUserAuthenticationRequired`, so no
 * enrollment or credential change voids it and there is no procedure that would. The reachable lost-key
 * outcomes are covered on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class DeviceKeyHardwareManualTest {
    private val logger = DefaultSdkLogger(LogCategory.CORE, RecordingLogSink())
    private val keyId = DeviceKeyHandle.ALIAS

    @Before
    fun setUp() {
        runCatching { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(keyId) }
    }

    @After
    fun tearDown() {
        runCatching { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(keyId) }
    }

    private fun provisioned() = KeystoreDeviceKey(logger).apply { ensureKey(mayCreate = true) }

    private fun keyInfo(): KeyInfo {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val private = store.getKey(keyId, null) as PrivateKey
        // KeyFactory rather than the SecretKeyFactory the storage tier uses: this key is asymmetric.
        return KeyFactory.getInstance(private.algorithm, PROVIDER).getKeySpec(private, KeyInfo::class.java)
    }

    /**
     * Whether this device is **required** to back its keystore with an isolated execution environment.
     *
     * `FEATURE_HARDWARE_KEYSTORE` was introduced at API 31, so an API 23 to 30 device does not advertise it
     * even when its keystore is hardware-backed, and querying it there would fail a correct implementation.
     * Below 31 the signal is `FEATURE_FINGERPRINT`: the Compatibility Definition Document requires at
     * `[9.11/H-0-2]` that a device back its keystore with an isolated execution environment, and exempts
     * devices launched earlier **unless** they declare that feature. One case is left unanswered, a pre-31
     * device without fingerprint, which is genuinely exempt and skips.
     */
    private fun requiresHardwareBackedKeystore(): Boolean {
        val features = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            features.hasSystemFeature(HARDWARE_KEYSTORE_FEATURE)
        } else {
            features.hasSystemFeature(FINGERPRINT_FEATURE)
        }
    }

    @ManualDeviceTest
    @Test
    fun theDeviceKeyIsHardwareBacked() =
        runTest(timeout = TEST_TIMEOUT) {
            Assume.assumeTrue(
                "this device advertises no hardware keystore, so there is no hardware backing to assert",
                requiresHardwareBackedKeystore(),
            )
            provisioned()
            val info = keyInfo()

            // The coarsest question, and deliberately silent about which level: losing StrongBox and losing
            // hardware entirely must not present as the same failure.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertNotEquals(
                    "the device key is software-backed on a device that has a secure element",
                    KeyProperties.SECURITY_LEVEL_SOFTWARE,
                    info.securityLevel,
                )
            } else {
                @Suppress("DEPRECATION")
                assertTrue("the device key is not inside secure hardware", info.isInsideSecureHardware)
            }
        }

    @ManualDeviceTest
    @Test
    fun theDeviceKeyUsesTheBestLevelTheDeviceAdvertises() =
        runTest(timeout = TEST_TIMEOUT) {
            provisioned()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Assume.assumeTrue(requiresHardwareBackedKeystore())
                @Suppress("DEPRECATION")
                assertTrue("the device key is not inside secure hardware", keyInfo().isInsideSecureHardware)
                return@runTest
            }

            val features = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
            val expected =
                when {
                    features.hasSystemFeature(STRONGBOX_FEATURE) -> KeyProperties.SECURITY_LEVEL_STRONGBOX
                    requiresHardwareBackedKeystore() -> KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
                    else -> KeyProperties.SECURITY_LEVEL_SOFTWARE
                }

            // The only test that catches a silent fall to a weaker level on a device that offers better.
            assertEquals(expected, keyInfo().securityLevel)
        }

    @ManualDeviceTest
    @Test
    fun aSignatureVerifiesUnderAHardwareBackedKey() =
        runTest(timeout = TEST_TIMEOUT) {
            Assume.assumeTrue(requiresHardwareBackedKeystore())
            val subject = provisioned()
            val payload = "the-signed-bytes".toByteArray()

            val signature = subject.sign(payload)

            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            val verified =
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(store.getCertificate(keyId).publicKey)
                    update(payload)
                    verify(signature)
                }
            assertTrue("a signature from a hardware-backed device key did not verify", verified)

            // Re-asserted, so the round trip cannot silently have run under a software key.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertNotEquals(KeyProperties.SECURITY_LEVEL_SOFTWARE, keyInfo().securityLevel)
            } else {
                @Suppress("DEPRECATION")
                assertTrue(keyInfo().isInsideSecureHardware)
            }
        }

    private companion object {
        val TEST_TIMEOUT = 30.seconds
        const val PROVIDER = "AndroidKeyStore"
        const val STRONGBOX_FEATURE = "android.hardware.strongbox_keystore"
        const val HARDWARE_KEYSTORE_FEATURE = "android.hardware.hardware_keystore"
        const val FINGERPRINT_FEATURE = "android.hardware.fingerprint"
    }
}
