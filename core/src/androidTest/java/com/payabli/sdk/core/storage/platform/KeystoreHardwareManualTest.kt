package com.payabli.sdk.core.storage.platform

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.ManualDeviceTest
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultPayabliLogger
import com.payabli.sdk.core.storage.impl.FileSecureStorage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlin.time.Duration.Companion.seconds

/**
 * What only real hardware can answer about the storage key. **Excluded from CI**; see [ManualDeviceTest].
 *
 * The emulator suite already covers the round trip, per-write IV freshness and the unreadable-key path. None
 * of that is repeated here. The one question left is whether the key is actually in secure hardware, which an
 * emulator answers `SOFTWARE` to no matter how correct the code is, so asserting it there would be a test
 * that can only fail or lie.
 *
 * Run it against a wired phone:
 * ```
 * ANDROID_SERIAL=<serial> ./gradlew :core:connectedAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.annotation=com.payabli.sdk.core.ManualDeviceTest
 * ```
 *
 * **Not covered here: `KeyPermanentlyInvalidatedException`.** This key is not bound to user authentication,
 * so an enrollment or credential change does not invalidate it, and there is no procedure that would. The
 * reachable lost-key outcomes, a deleted alias and a replaced one, are covered by the emulator suite.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreHardwareManualTest {
    private val logger = DefaultPayabliLogger(LogCategory.CORE, RecordingLogSink())
    private lateinit var directory: File
    private lateinit var keyAlias: String

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.noBackupFilesDir, "secure-storage-manual").apply { mkdirs() }
        keyAlias = "payabli-manual-${System.nanoTime()}"
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        runCatching { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(keyAlias) }
    }

    private fun storage() =
        FileSecureStorage(
            file = File(directory, "store.json"),
            cipher = KeystoreValueCipher(keyAlias, logger),
            logger = logger,
        )

    /**
     * The key must live in secure hardware, at the best level the device offers.
     *
     * The assertion an emulator cannot host: measured there, this fails with `SECURITY_LEVEL_SOFTWARE`, which
     * is exactly why the tier is excluded from CI rather than parked with an `Assume`.
     *
     * StrongBox is folded in here rather than kept as its own test, so that nothing in this file is ever
     * reported as skipped. A device advertising `strongbox_keystore` has to produce a StrongBox-backed key;
     * one without it has to produce a TEE-backed key. Both are correct outcomes and each is checked against
     * what the device actually claims, so there is no case left where the test has nothing to say.
     *
     * `KeyInfo.getSecurityLevel` is API 31, so 23 to 30 falls back to `isInsideSecureHardware`, the same
     * question at lower resolution: it cannot tell StrongBox from a TEE. The failure it catches is the same
     * one either way, a software-backed key.
     */
    @ManualDeviceTest
    @Test
    fun theStorageKeyLivesInSecureHardwareAtTheDevicesBestLevel() =
        runTest(timeout = 30.seconds) {
            storage().set("refresh", "secret-value".toCharArray())
            val info = keyInfo()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                assertTrue("the storage key is not inside secure hardware", info.isInsideSecureHardware)
                return@runTest
            }

            val hasStrongBox =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    InstrumentationRegistry
                        .getInstrumentation()
                        .targetContext
                        .packageManager
                        .hasSystemFeature(STRONGBOX_FEATURE)

            val expected =
                if (hasStrongBox) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX
                } else {
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
                }
            assertEquals(
                "expected the best level this device offers (strongbox present: $hasStrongBox)",
                expected,
                info.securityLevel,
            )
        }

    /** A round trip on real hardware, so the hardware path is exercised end to end and not only inspected. */
    @ManualDeviceTest
    @Test
    fun aValueRoundTripsOnRealHardware() =
        runTest(timeout = 30.seconds) {
            val subject = storage()
            subject.set("refresh", "secret-value".toCharArray())
            assertArrayEquals("secret-value".toCharArray(), subject.get("refresh"))
        }

    private fun keyInfo(): KeyInfo {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val key = store.getKey(keyAlias, null) as SecretKey
        val factory = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
        return factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val STRONGBOX_FEATURE = "android.hardware.strongbox_keystore"
    }
}
