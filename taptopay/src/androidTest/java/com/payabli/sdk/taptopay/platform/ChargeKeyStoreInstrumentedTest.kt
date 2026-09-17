package com.payabli.sdk.taptopay.platform

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE
import com.payabli.sdk.taptopay.ChargeKeyStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 60.seconds

/**
 * The held charge key on device: process memory only, and no blob in the encrypted store.
 */
@RunWith(AndroidJUnit4::class)
class ChargeKeyStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storeFile get() = File(context.noBackupFilesDir, "payabli-secure-store.json")
    private val clock = AtomicLong(SystemClock.elapsedRealtimeNanos())

    @Before
    @After
    fun clean() =
        runTest(timeout = TEST_TIMEOUT) {
            ChargeKeyStore.forgetHeld()
            val trust = DeviceTrust.open(context)
            runCatching { trust.store.remove(ChargeKeyStore.LEGACY_ENTRY) }
            runCatching { trust.store.remove(ChargeKeyStore.LEGACY_PREVIOUS_ENTRY) }
        }

    private fun storeOver(minted: String) =
        ChargeKeyStore(
            newKey = { minted },
            elapsedRealtimeNanos = clock::get,
        )

    @Test
    fun aReservationSurvivesAFreshStoreInstance() =
        runTest(timeout = TEST_TIMEOUT) {
            assertFalse(storeOver("minted-1").reserve(ENTRY, PayabliEnvironment.SANDBOX).reused)

            val second = storeOver("minted-2").reserve(ENTRY, PayabliEnvironment.SANDBOX)

            assertEquals("minted-1", second.key)
            assertTrue(second.reused)
        }

    @Test
    fun aSettledKeyIsGoneFromTheProcessMap() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver("minted-1")
            store.reserve(ENTRY, PayabliEnvironment.SANDBOX)

            store.settle(ENTRY, PayabliEnvironment.SANDBOX, "minted-1")

            assertEquals("minted-2", storeOver("minted-2").reserve(ENTRY, PayabliEnvironment.SANDBOX).key)
        }

    @Test
    fun anExpiredKeyIsNotResent() =
        runTest(timeout = TEST_TIMEOUT) {
            storeOver("minted-1").reserve(ENTRY, PayabliEnvironment.SANDBOX)
            clock.addAndGet(IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds)
            assertEquals("minted-2", storeOver("minted-2").reserve(ENTRY, PayabliEnvironment.SANDBOX).key)
        }

    @Test
    fun theStoreWritesNoChargeKeyEntryToTheEncryptedFile() =
        runTest(timeout = TEST_TIMEOUT) {
            storeOver("minted-1").reserve(ENTRY, PayabliEnvironment.SANDBOX)

            if (!storeFile.exists()) return@runTest
            val contents = storeFile.readText()
            assertFalse(contents.contains(ChargeKeyStore.LEGACY_ENTRY))
            assertFalse(contents.contains(ChargeKeyStore.LEGACY_PREVIOUS_ENTRY))
            assertFalse(contents.contains("minted-1"))
            assertFalse(contents.contains(ENTRY))
        }

    @Test
    fun aLegacyBlobIsLeftUnreadAndCanBeRemoved() =
        runTest(timeout = TEST_TIMEOUT) {
            val trust = DeviceTrust.open(context)
            trust.store.set(
                ChargeKeyStore.LEGACY_ENTRY,
                """{"attempts":[{"entry":"$ENTRY","key":"stale-from-disk","reservedAt":1}]}""".encodeToByteArray(),
            )

            val reserved = storeOver("minted-1").reserve(ENTRY, PayabliEnvironment.SANDBOX)

            assertEquals("minted-1", reserved.key)
            assertFalse(reserved.reused)
            trust.store.remove(ChargeKeyStore.LEGACY_ENTRY)
            assertNull(trust.store.get(ChargeKeyStore.LEGACY_ENTRY))
        }

    private companion object {
        const val ENTRY = "entry-under-test"
    }
}
