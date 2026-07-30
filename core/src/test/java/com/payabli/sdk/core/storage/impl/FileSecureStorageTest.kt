package com.payabli.sdk.core.storage.impl

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultPayabliLogger
import com.payabli.sdk.core.storage.SecureStorageException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The persistence half of secure storage, against a fake cipher.
 *
 * Everything here is ordinary logic that would otherwise need an emulator: atomic replace, concurrent
 * read-modify-write, removal, and a corrupt file. The real Keystore cipher is covered separately by
 * `KeystoreValueCipherInstrumentedTest`, which is the only part that genuinely needs a device.
 *
 * Bounded `runTest` throughout: a lock bug here would otherwise hang for the framework's 60s default and
 * report `UncompletedCoroutinesError`, which names nothing.
 */
class FileSecureStorageTest {
    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val sink = RecordingLogSink()

    /**
     * Varies its output per call, exactly as the real cipher does via a per-write IV, so a test asserting
     * "two writes of the same value differ on disk" is meaningful rather than tautological.
     */
    private class CountingCipher(
        var failNextDecrypt: SecureStorageException? = null,
        /**
         * Widens the read-modify-write window, for the concurrency test only.
         *
         * Load-bearing rather than a smell. A barrier is the usual way to force an interleaving, and the
         * one this repo used for the refresh de-duplication test, but a barrier inside the critical section
         * would deadlock the *correct* implementation: the first writer holds the lock while waiting for
         * writers who cannot enter it. A sleep inside the section deadlocks nothing and still makes the
         * failure deterministic, because every writer reads the map before any of them writes.
         */
        private val delayMillis: Long = 0,
    ) : ValueCipher {
        @Volatile
        var encryptions: Int = 0
            private set

        override fun encrypt(plaintext: String): String {
            val ordinal = synchronized(this) { ++encryptions }
            if (delayMillis > 0) Thread.sleep(delayMillis)
            return "$ordinal:$plaintext"
        }

        override fun decrypt(blob: String): String {
            failNextDecrypt?.let {
                failNextDecrypt = null
                throw it
            }
            return blob.substringAfter(':')
        }
    }

    private fun storage(
        file: File = File(folder.root, "store.json"),
        cipher: ValueCipher = CountingCipher(),
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) = FileSecureStorage(
        file = file,
        cipher = cipher,
        logger = DefaultPayabliLogger(LogCategory.CORE, sink),
        dispatcher = dispatcher,
    )

    @Test
    fun `a value round-trips`() =
        runTest(timeout = 5.seconds) {
            val subject = storage()
            subject.set("refresh", "secret-value")
            assertEquals("secret-value", subject.get("refresh"))
        }

    @Test
    fun `an absent key reads as null rather than failing`() =
        runTest(timeout = 5.seconds) {
            assertNull(storage().get("never-written"))
        }

    /** The point of persisting at all: a second instance must see it, not just the one that wrote it. */
    @Test
    fun `a value survives a new instance over the same file`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            storage(file).set("refresh", "secret-value")
            assertEquals("secret-value", storage(file).get("refresh"))
        }

    @Test
    fun `a second write replaces the first`() =
        runTest(timeout = 5.seconds) {
            val subject = storage()
            subject.set("refresh", "first")
            subject.set("refresh", "second")
            assertEquals("second", subject.get("refresh"))
        }

    @Test
    fun `remove deletes the value and is silent about an absent key`() =
        runTest(timeout = 5.seconds) {
            val subject = storage()
            subject.set("refresh", "secret-value")
            subject.remove("refresh")
            assertNull(subject.get("refresh"))
            subject.remove("never-written")
        }

    /**
     * The load-bearing test for the write path, and the reason the fake varies its output.
     *
     * Two writes of the same plaintext must produce different bytes on disk. Asserting only that the value
     * round-trips would pass with a cipher that reused its IV, which is the defect this guards.
     */
    @Test
    fun `writing the same value twice stores different bytes`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            val subject = storage(file)
            subject.set("refresh", "same")
            val first = file.readText()
            subject.set("refresh", "same")
            val second = file.readText()

            // Only that the bytes changed. Whether the plaintext is absent cannot be asserted here, because
            // the fake cipher embeds it on purpose; that belongs to the instrumented test, where the cipher
            // is real. Asserting it against a fake would be a test of the fake.
            assertTrue("the stored blob did not change between writes", first != second)
        }

    /**
     * Concurrent writers, which is where a read-modify-write store loses data.
     *
     * Without the mutex each writer reads the map, adds its own key and writes the whole thing back, so the
     * last writer wins and the other nine vanish.
     *
     * **Two things here are deliberate, and the test proved worthless without them.** A real multi-threaded
     * dispatcher, because `Dispatchers.Unconfined` runs each `async` to completion before starting the next
     * and nothing ever interleaves. And a delay inside the critical section, so every writer has read the
     * map before any writes it back. With the first version, deleting the mutex left this test green, which
     * is the same defect as a timing test that waits out its own stall.
     */
    @Test
    fun `concurrent writes do not lose values`() =
        runTest(timeout = 30.seconds) {
            val subject =
                storage(cipher = CountingCipher(delayMillis = 50), dispatcher = Dispatchers.IO)
            (1..10).map { async(Dispatchers.IO) { subject.set("key-$it", "value-$it") } }.awaitAll()

            (1..10).forEach {
                assertEquals("value-$it was dropped by a concurrent write", "value-$it", subject.get("key-$it"))
            }
        }

    /**
     * A corrupt file resets rather than bricking the store.
     *
     * Every value in here is ciphertext the caller can re-obtain, so starting over strands nobody, whereas
     * refusing to load would make one bad write permanent.
     */
    @Test
    fun `a corrupt file is reset rather than failing every call`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            file.writeText("{ this is not json")

            val subject = storage(file)
            assertNull(subject.get("refresh"))
            subject.set("refresh", "secret-value")
            assertEquals("secret-value", subject.get("refresh"))
            assertTrue(
                "the reset should be logged rather than silent",
                sink.records.any { it.message.contains("unreadable") },
            )
        }

    /**
     * Key invalidation must reach the caller.
     *
     * Returning null would be indistinguishable from "nothing stored", and the caller's required response
     * differs: absence means carry on, invalidation means re-authenticate. SEC-001 Section 9.3.
     */
    @Test
    fun `key invalidation propagates instead of reading as absent`() =
        runTest(timeout = 5.seconds) {
            val cipher = CountingCipher()
            val subject = storage(cipher = cipher)
            subject.set("refresh", "secret-value")

            cipher.failNextDecrypt = SecureStorageException.KeyInvalidated()
            val thrown = runCatching { subject.get("refresh") }.exceptionOrNull()

            assertTrue(
                "expected KeyInvalidated, got $thrown",
                thrown is SecureStorageException.KeyInvalidated,
            )
        }

    /** A crash-safe write leaves no debris, so the directory does not fill with temp files. */
    @Test
    fun `no temporary files are left behind`() =
        runTest(timeout = 5.seconds) {
            val subject = storage()
            subject.set("a", "1")
            subject.set("b", "2")

            val leftovers =
                folder.root
                    .listFiles()
                    ?.filter { it.name.endsWith(".tmp") }
                    .orEmpty()
            assertTrue("temp files left behind: $leftovers", leftovers.isEmpty())
        }
}
