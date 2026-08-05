package com.payabli.sdk.core.devicekey.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.devicekey.DeviceKey
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.logging.impl.LogSink
import com.payabli.sdk.core.storage.platform.SecureStorageFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private const val PROVIDER = "AndroidKeyStore"
private const val DISPATCH_THREAD = "device-key-test-dispatcher"
private val TEST_TIMEOUT = 30.seconds

/**
 * The composition itself: the directory, the filename, and the identity that ties them to one Keystore alias.
 *
 * Every part this assembles is tested on its own, and none of those tests can see this. The slot tests run over
 * in-memory storage, and the Keystore tests construct [KeystoreDeviceKey] against an alias they chose, so the
 * wiring that decides *which* store a key is named by is exactly what stays green while it breaks. A directory
 * that changed between calls, or a second identity resolved for the same file, would mint a new key on every
 * call and strand the one before it, and both component suites would still pass.
 *
 * This runs against the real store the app would use. It is deleted before and after each test, so a leftover
 * from an earlier run cannot stand in for a key this test believes it minted.
 */
@RunWith(AndroidJUnit4::class)
class DeviceKeyFactoryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logger = DefaultSdkLogger(LogCategory.CORE, RecordingLogSink())

    /** What the integrating layer would choose. Named once here, since nothing below it supplies a default. */
    private val dispatcher = Dispatchers.IO

    /** Every alias this test caused to exist, so teardown deletes each one from the device's Keystore. */
    private val minted = mutableListOf<String>()

    @Before
    fun clearStore() = wipe()

    @After
    fun tearDown() = wipe()

    private fun storeFile() = File(context.applicationContext.noBackupFilesDir, DeviceKeyFactory.FILE_NAME)

    private fun wipe() {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        minted.forEach { runCatching { keyStore.deleteEntry(it) } }
        minted.clear()
        // The store's own key, which is separate from the device keys it names.
        runCatching { keyStore.deleteEntry(SecureStorageFactory.aliasFor(storeFile())) }
        storeFile().delete()
    }

    private suspend fun candidate(): DeviceKey =
        DeviceKeyFactory.candidate(context, dispatcher, logger).also {
            minted +=
                it.keyId
        }

    @Test
    fun theStoreLandsInTheNoBackupDirectoryUnderItsOwnName() =
        runTest(timeout = TEST_TIMEOUT) {
            candidate()

            // Named concretely, because the two mistakes this guards against are silent: a store in filesDir
            // travels in backup and device transfer, where the restored name points at a key that did not
            // travel with it, and a store sharing the token file is cleared whenever that file is.
            assertTrue("the device-key store is not where the factory documents it", storeFile().isFile)
            assertEquals("payabli-devicekey.json", storeFile().name)
        }

    @Test
    fun aSecondCallReturnsTheKeyTheFirstMinted() =
        runTest(timeout = TEST_TIMEOUT) {
            val first = candidate()
            val second = candidate()

            // A retry before attestation must attest the key it already has. Minting a second would leave the
            // first named by nothing, once per attempt.
            assertEquals(first.keyId, second.keyId)
            assertArrayEquals(first.publicKeyPoint(), second.publicKeyPoint())
        }

    @Test
    fun aMintedCandidateIsNotYetActive() =
        runTest(timeout = TEST_TIMEOUT) {
            assertNull("nothing is attested on a clean store", DeviceKeyFactory.active(context, dispatcher, logger))

            candidate()

            // Minting is not attestation. Reporting a candidate as active would sign with material the service
            // has never seen.
            assertNull(
                "a minted candidate is not an attested one",
                DeviceKeyFactory.active(context, dispatcher, logger),
            )
        }

    /**
     * The blocking work runs on the dispatcher it was given, not on the caller's thread.
     *
     * Both halves matter. A suspend signature does not make a function main-safe, and the two blocking things
     * here sit between the suspending reads: resolving the store's canonical path, and every Keystore call,
     * generation included. On Main that is a stall for as long as a secure element takes.
     *
     * Observed through the line `createKey` emits, the one point inside the dispatched body that reports
     * which thread it ran on. The dispatcher is a single thread with a known name, so the assertion is an
     * equality on that name.
     */
    @Test
    fun theKeystoreWorkRunsOnTheDispatcherItWasGiven() =
        runTest(timeout = TEST_TIMEOUT) {
            val generatedOn = AtomicReference<String>()
            val watching =
                DefaultSdkLogger(
                    LogCategory.CORE,
                    object : LogSink {
                        override fun isLoggable(
                            level: LogLevel,
                            tag: String,
                        ): Boolean = true

                        override fun write(
                            level: LogLevel,
                            tag: String,
                            message: String,
                        ) {
                            if ("device key created" in message) generatedOn.set(Thread.currentThread().name)
                        }
                    },
                )
            val executor = Executors.newSingleThreadExecutor { Thread(it, DISPATCH_THREAD) }

            try {
                val key = DeviceKeyFactory.candidate(context, executor.asCoroutineDispatcher(), watching)
                minted += key.keyId

                assertEquals(DISPATCH_THREAD, generatedOn.get())
            } finally {
                executor.shutdown()
            }
        }

    @Test
    fun promotingTheCandidateMakesTheSameKeyActive() =
        runTest(timeout = TEST_TIMEOUT) {
            val candidate = candidate()

            val promoted = DeviceKeyFactory.slots(context, dispatcher, logger).promotePending()
            val active = DeviceKeyFactory.active(context, dispatcher, logger)

            // The same key, not merely a key: the alias survives the round trip through the store, and the
            // Keystore entry it names is the one attestation was performed against.
            assertEquals(candidate.keyId, promoted)
            assertNotNull("the promoted alias did not resolve to a key", active)
            assertEquals(candidate.keyId, active?.keyId)
            assertArrayEquals(candidate.publicKeyPoint(), active?.publicKeyPoint())
        }
}
