package com.payabli.sdk.core.devicekey.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.payabli.sdk.core.devicekey.DeviceKey
import com.payabli.sdk.core.devicekey.impl.DeviceKeyHandle
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.logging.impl.LogSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private const val PROVIDER = "AndroidKeyStore"
private const val DISPATCH_THREAD = "device-key-test-dispatcher"
private val TEST_TIMEOUT = 30.seconds

/**
 * That one key exists at one alias, and that replacing it leaves nothing behind.
 *
 * Only a real key store answers this. On the JVM there is no `AndroidKeyStore` to hold a stray key, so a
 * version of this test off-device would assert against a map it built itself.
 *
 * **This runs against the alias the app itself uses**, which is the only alias there is, so it deletes that
 * entry before and after each test. A leftover from an earlier run would otherwise stand in for a key the
 * test believes it generated.
 */
@RunWith(AndroidJUnit4::class)
class DeviceKeyFactoryInstrumentedTest {
    private val logger = DefaultSdkLogger(LogCategory.CORE, RecordingLogSink())

    /** What the integrating layer would choose. Named once here, since nothing below it supplies a default. */
    private val dispatcher = Dispatchers.IO

    @Before
    fun clearStore() = wipe()

    @After
    fun tearDown() = wipe()

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun wipe() {
        runCatching { keyStore().deleteEntry(DeviceKeyHandle.ALIAS) }
        Unit
    }

    private suspend fun deviceKey(): DeviceKey = DeviceKeyFactory.deviceKey(dispatcher, logger)

    /**
     * Every entry in this SDK's device-key namespace.
     *
     * **The test enumerates and production must not.** Enumeration is the only way to observe a key that
     * nothing names, which is exactly the failure this asserts cannot happen, so the check has to be able to
     * see what the code under test is forbidden to look for.
     */
    private fun entriesInNamespace(): List<String> =
        keyStore().aliases().toList().filter { it.startsWith(DeviceKeyHandle.ALIAS) }

    @Test
    fun theKeyLandsAtTheFixedAlias() =
        runTest(timeout = TEST_TIMEOUT) {
            deviceKey()

            assertEquals(listOf(DeviceKeyHandle.ALIAS), entriesInNamespace())
        }

    @Test
    fun aSecondCallReturnsTheKeyTheFirstGenerated() =
        runTest(timeout = TEST_TIMEOUT) {
            val first = deviceKey()
            val second = deviceKey()

            // The reuse a retry depends on, and it comes from the alias being fixed rather than from a stored
            // name. Generating a second key would leave the first with nothing able to name it.
            assertEquals(first.identity(), second.identity())
            assertArrayEquals(first.publicKeyPoint(), second.publicKeyPoint())
        }

    /**
     * Replacing the key leaves exactly one entry, however many times it is replaced.
     *
     * The whole safety argument for a fixed alias. Under a generated name, each replacement adds an entry
     * beside the last and only the newest is reachable; here the namespace is asserted to hold one.
     */
    @Test
    fun replacingTheKeyLeavesExactlyOneEntry() =
        runTest(timeout = TEST_TIMEOUT) {
            val identities = mutableListOf<String>()

            repeat(3) {
                val key = deviceKey()
                identities += key.identity()
                key.delete()
            }
            val survivor = deviceKey()

            assertEquals(listOf(DeviceKeyHandle.ALIAS), entriesInNamespace())
            // Each replacement is a different key, so the identifier the service records changes with it even
            // though the alias does not. Equal identities here would mean nothing was actually replaced.
            assertEquals(identities.size, identities.toSet().size)
            assertNotEquals(identities.last(), survivor.identity())
        }

    @Test
    fun deletingLeavesNothingInTheNamespace() =
        runTest(timeout = TEST_TIMEOUT) {
            deviceKey().delete()

            assertEquals(emptyList<String>(), entriesInNamespace())
        }

    /**
     * The blocking work runs on the dispatcher it was given, not on the caller's thread.
     *
     * A suspend signature does not make a function main-safe, and every Keystore call here is blocking,
     * generation included. On Main that is a stall for as long as a secure element takes.
     *
     * Observed through the line `createKey` emits, the one point inside the dispatched body that reports which
     * thread it ran on. The dispatcher is a single thread with a known name, so the assertion is an equality
     * on that name.
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
                DeviceKeyFactory.deviceKey(executor.asCoroutineDispatcher(), watching)

                assertEquals(DISPATCH_THREAD, generatedOn.get())
            } finally {
                executor.shutdown()
            }
        }
}
