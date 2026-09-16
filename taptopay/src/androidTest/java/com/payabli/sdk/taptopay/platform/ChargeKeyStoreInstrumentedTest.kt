package com.payabli.sdk.taptopay.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.devicetrust.platform.DeviceTrust
import com.payabli.sdk.taptopay.ChargeKeyStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 60.seconds

/**
 * The held charge key against the real encrypted store.
 *
 * The unit tier writes plaintext into a map, so what the record costs on a device is unfalsifiable there:
 * whether a key written by the previous version decodes into this one, and whether the key is readable in
 * the file. Both decide whether a merchant can charge after an upgrade.
 */
@RunWith(AndroidJUnit4::class)
class ChargeKeyStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storeFile get() = File(context.noBackupFilesDir, "payabli-secure-store.json")
    private val clock = AtomicLong(1_700_000_000_000)

    @Before
    @After
    fun clean() =
        runTest(timeout = TEST_TIMEOUT) {
            val trust = DeviceTrust.open(context)
            trust.store.remove(ChargeKeyStore.ENTRY)
            trust.store.remove(ChargeKeyStore.PREVIOUS_ENTRY)
            ChargeKeyStore.forgetSettled()
        }

    private suspend fun storeOver(minted: String) =
        ChargeKeyStore(
            DeviceTrust.open(context).store,
            newKey = { minted },
            nowMillis = clock::get,
        )

    /**
     * A key written by the previous version is carried through the real cipher.
     *
     * Failing to decode it is what stops every charge on the device until the transactions it names are
     * resolved outside the app, so an upgrade meeting one has to carry it rather than raise.
     */
    @Test
    fun aKeyFromThePreviousRecordIsCarriedThroughTheRealCipher() =
        runTest(timeout = TEST_TIMEOUT) {
            val trust = DeviceTrust.open(context)
            trust.store.set(
                ChargeKeyStore.PREVIOUS_ENTRY,
                """{"attempts":[{"entry":"$ENTRY","key":"$CARRIED_KEY"}]}""".encodeToByteArray(),
            )

            val reserved = storeOver("minted-1").reserve(ENTRY)

            assertEquals(CARRIED_KEY, reserved.key)
            assertTrue("the carried key was not recognised as held", reserved.reused)
            assertNull("the previous record was left behind", trust.store.get(ChargeKeyStore.PREVIOUS_ENTRY))
            assertNotNull(trust.store.get(ChargeKeyStore.ENTRY))
        }

    /** And it is bounded from the moment it is carried, rather than held forever. */
    @Test
    fun aCarriedKeyExpiresLikeAnyOther() =
        runTest(timeout = TEST_TIMEOUT) {
            DeviceTrust.open(context).store.set(
                ChargeKeyStore.PREVIOUS_ENTRY,
                """{"attempts":[{"entry":"$ENTRY","key":"$CARRIED_KEY"}]}""".encodeToByteArray(),
            )
            storeOver("minted-1").reserve(ENTRY)

            clock.addAndGet(TimeUnit.MINUTES.toMillis(3))

            assertEquals("minted-2", storeOver("minted-2").reserve(ENTRY).key)
        }

    /** One store's reservation is another's held key, which is what a rebuilt terminal depends on. */
    @Test
    fun aReservationSurvivesAFreshOpen() =
        runTest(timeout = TEST_TIMEOUT) {
            assertFalse(storeOver("minted-1").reserve(ENTRY).reused)

            val second = storeOver("minted-2").reserve(ENTRY)

            assertEquals("minted-1", second.key)
            assertTrue(second.reused)
        }

    /** A settled charge leaves nothing for the next one to resend. */
    @Test
    fun aSettledKeyIsGoneFromTheRealStore() =
        runTest(timeout = TEST_TIMEOUT) {
            val store = storeOver("minted-1")
            store.reserve(ENTRY)

            store.settle(ENTRY, "minted-1")

            assertEquals("minted-2", storeOver("minted-2").reserve(ENTRY).key)
        }

    /** The entry point names a merchant and the key names an attempt at moving their money. */
    @Test
    fun theHeldKeyIsNotReadableAsPlaintextInTheFile() =
        runTest(timeout = TEST_TIMEOUT) {
            storeOver("minted-1").reserve(ENTRY)

            val contents = storeFile.readText()

            assertTrue(contents.contains(ChargeKeyStore.ENTRY))
            assertFalse(contents.contains("minted-1"))
            assertFalse(contents.contains(ENTRY))
        }

    private companion object {
        const val ENTRY = "entry-under-test"
        const val CARRIED_KEY = "carried-key-from-the-previous-record"
    }
}
