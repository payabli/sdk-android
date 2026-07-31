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
import org.junit.Assert.assertNotEquals
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
 * The emulator suite covers the round trip, per-write IV freshness, the unreadable-key path, the authorizations
 * the key was created with, and the StrongBox **fallback**. None of that is repeated here. What is left needs a
 * secure element, which an emulator answers `SOFTWARE` to no matter how correct the code is.
 *
 * **One question per test, because they are not the same question.** Whether the key is in secure hardware at
 * all, whether it reached the best level the device offers, and whether a value decrypts through it are three
 * separate failures with three separate causes. A single test that branches on device capability runs half of
 * itself on any given phone, and the half that did not run reads as covered.
 *
 * Run it against every wired phone, not one. Vendors differ: measured here, a Pixel 7a reports
 * `hardware_keystore=400` with `strongbox_keystore=300`, and a Samsung SM-S908U1 reports `100` with `4`.
 * ```
 * ANDROID_SERIAL=<serial> ./gradlew :core:connectedAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.annotation=com.payabli.sdk.core.ManualDeviceTest
 * ```
 *
 * **A gap worth naming rather than hiding:** both phones available here advertise StrongBox, so the
 * `TRUSTED_ENVIRONMENT` branch of [theStorageKeyUsesTheBestLevelTheDeviceAdvertises] has no hardware to execute
 * it. It is asserted by construction, not by a run.
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
     * Is the key in secure hardware **at all**? The coarsest question, and the one that must never regress.
     *
     * Deliberately says nothing about which level. A TEE-backed key passes here and that is correct: this
     * catches the one outcome that is always wrong on a phone, a software-backed key. Kept separate from the
     * level test so that losing StrongBox and losing hardware entirely cannot present as the same failure.
     *
     * `KeyInfo.getSecurityLevel` is API 31, so 23 to 30 asks `isInsideSecureHardware`, the same question at
     * lower resolution. It cannot tell StrongBox from a TEE, which is precisely the distinction this test does
     * not make anyway.
     */
    @ManualDeviceTest
    @Test
    fun theStorageKeyIsHardwareBacked() =
        runTest(timeout = 30.seconds) {
            storage().set("refresh", "secret-value".toByteArray())
            val info = keyInfo()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertNotEquals(
                    "the storage key is software-backed on a device that has a secure element",
                    KeyProperties.SECURITY_LEVEL_SOFTWARE,
                    info.securityLevel,
                )
            } else {
                @Suppress("DEPRECATION")
                assertTrue("the storage key is not inside secure hardware", info.isInsideSecureHardware)
            }
        }

    /**
     * Is it at the **best level this device advertises**? A different failure from the one above.
     *
     * A silent StrongBox fallback on a device that has StrongBox leaves a TEE key, which is hardware-backed and
     * therefore invisible to [theStorageKeyIsHardwareBacked]. Only this test sees it. The capability branch
     * belongs here because it *is* the question, rather than being a device adaptation bolted onto something
     * else.
     *
     * Below 31 the platform cannot report a level at all, so the honest thing to assert at that resolution is
     * hardware backing, which is what it does.
     *
     * **The `TRUSTED_ENVIRONMENT` branch is unexecuted here.** Both phones on hand advertise StrongBox, so no
     * available device takes it, and the emulator has no secure element at all.
     */
    @ManualDeviceTest
    @Test
    fun theStorageKeyUsesTheBestLevelTheDeviceAdvertises() =
        runTest(timeout = 30.seconds) {
            storage().set("refresh", "secret-value".toByteArray())
            val info = keyInfo()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                assertTrue("the storage key is not inside secure hardware", info.isInsideSecureHardware)
                return@runTest
            }

            val hasStrongBox =
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
                "expected the best level this device offers (strongbox advertised: $hasStrongBox)",
                expected,
                info.securityLevel,
            )
        }

    /**
     * Does a value **decrypt through** a hardware-backed key?
     *
     * Not a copy of the emulator's round trip, and the level assertion is what makes it not one: this proves the
     * secure element performed the operation, where the emulator proves only that the code is symmetric. Kept
     * apart from the two level tests because a decrypt failure under a correctly-provisioned key is its own
     * defect, and folding it in was a mistake corrected here.
     */
    @ManualDeviceTest
    @Test
    fun aValueRoundTripsUnderAHardwareBackedKey() =
        runTest(timeout = 30.seconds) {
            val subject = storage()
            subject.set("refresh", "secret-value".toByteArray())

            assertArrayEquals(
                "the value did not survive a round trip on real hardware",
                "secret-value".toByteArray(),
                subject.get("refresh"),
            )

            val info = keyInfo()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertNotEquals(
                    "the round trip ran under a software key, so it proves nothing this tier is for",
                    KeyProperties.SECURITY_LEVEL_SOFTWARE,
                    info.securityLevel,
                )
            } else {
                @Suppress("DEPRECATION")
                assertTrue("the round trip did not run under a hardware-backed key", info.isInsideSecureHardware)
            }
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
