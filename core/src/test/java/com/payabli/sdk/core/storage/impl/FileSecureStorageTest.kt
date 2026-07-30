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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The persistence half of secure storage, against a fake cipher: atomic replace, concurrent
 * read-modify-write, removal, corruption, and how each failure is cleaned up. The real Keystore is covered
 * by the instrumented suite.
 *
 * Every `runTest` is bounded, so a lock bug fails in milliseconds naming the defect rather than hanging.
 */
class FileSecureStorageTest {
    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val sink = RecordingLogSink()

    /**
     * Varies its output per call, like the real per-write IV, and binds the AAD so a blob is only valid
     * under the entry it was sealed with. Without that the swap test below would pass regardless.
     */
    private class CountingCipher(
        var failNextDecrypt: SecureStorageException? = null,
        /**
         * Widens the read-modify-write window, for the concurrency tests only.
         *
         * A barrier would deadlock the correct implementation, since the first writer holds the lock while
         * waiting for writers that cannot enter it. A sleep deadlocks nothing, and it lands inside the
         * window only because `set` reads before encrypting.
         */
        private val delayMillis: Long = 0,
    ) : ValueCipher {
        @Volatile
        var encryptions: Int = 0
            private set

        override fun encrypt(
            aad: String,
            plaintext: ByteArray,
        ): String {
            val ordinal = synchronized(this) { ++encryptions }
            if (delayMillis > 0) Thread.sleep(delayMillis)
            return "$ordinal|$aad|${String(plaintext, Charsets.UTF_8)}"
        }

        override fun decrypt(
            aad: String,
            blob: String,
        ): ByteArray {
            failNextDecrypt?.let {
                failNextDecrypt = null
                throw it
            }
            val parts = blob.split('|', limit = 3)
            // The AAD check the real cipher gets from GCM: a blob under the wrong name must not open.
            if (parts[1] != aad) throw SecureStorageException.ValueUnreadable()
            return parts[2].toByteArray(Charsets.UTF_8)
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
            subject.set("refresh", "secret-value".toCharArray())
            assertArrayEquals("secret-value".toCharArray(), subject.get("refresh"))
        }

    @Test
    fun `an absent key reads as null rather than failing`() =
        runTest(timeout = 5.seconds) {
            assertNull(storage().get("never-written"))
        }

    /** The point of persisting: a second instance must see it, not just the one that wrote it. */
    @Test
    fun `a value survives a new instance over the same file`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            storage(file).set("refresh", "secret-value".toCharArray())
            assertArrayEquals("secret-value".toCharArray(), storage(file).get("refresh"))
        }

    @Test
    fun `a second write replaces the first`() =
        runTest(timeout = 5.seconds) {
            val subject = storage()
            subject.set("refresh", "first".toCharArray())
            subject.set("refresh", "second".toCharArray())
            assertArrayEquals("second".toCharArray(), subject.get("refresh"))
        }

    /**
     * Guards the atomic replace: the write path must overwrite an existing store, which is what removing
     * the pre-delete depends on. If `renameTo` did not replace, this is where it would show.
     */
    @Test
    fun `a write replaces an existing store file`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            val subject = storage(file)
            subject.set("a", "1".toCharArray())
            assertTrue("the store was not created", file.exists())
            subject.set("b", "2".toCharArray())

            assertArrayEquals("1".toCharArray(), subject.get("a"))
            assertArrayEquals("2".toCharArray(), subject.get("b"))
        }

    @Test
    fun `remove deletes the value and is silent about an absent key`() =
        runTest(timeout = 5.seconds) {
            val subject = storage()
            subject.set("refresh", "secret-value".toCharArray())
            subject.remove("refresh")
            assertNull(subject.get("refresh"))
            subject.remove("never-written")
        }

    /** Two writes of one value must differ on disk; a round-trip test alone passes with a reused IV. */
    @Test
    fun `writing the same value twice stores different bytes`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            val subject = storage(file)
            subject.set("refresh", "same".toCharArray())
            val first = file.readText()
            subject.set("refresh", "same".toCharArray())

            assertTrue("the stored blob did not change between writes", first != file.readText())
        }

    /**
     * Swapping two blobs must not yield the other entry's secret.
     *
     * Without AAD every blob is valid under any name, since all entries share one key, so this returned the
     * wrong secret with the tag check passing. A substitution attack, not a corruption case.
     */
    @Test
    fun `swapping two blobs makes the read fail rather than returning the wrong secret`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            val subject = storage(file)
            subject.set("alpha", "alpha-secret".toCharArray())
            subject.set("beta", "beta-secret".toCharArray())

            val text = file.readText()
            val alpha = Regex("\"alpha\":\"([^\"]+)\"").find(text)!!.groupValues[1]
            val beta = Regex("\"beta\":\"([^\"]+)\"").find(text)!!.groupValues[1]
            file.writeText(text.replace(alpha, "SWAP").replace(beta, alpha).replace("SWAP", beta))

            val thrown = runCatching { subject.get("alpha") }.exceptionOrNull()
            assertTrue(
                "alpha returned another entry's secret instead of failing, got $thrown",
                thrown is SecureStorageException.ValueUnreadable,
            )
        }

    /**
     * Concurrent writers, where a read-modify-write store loses data: without the lock each reads the map,
     * adds its key and writes it all back, so the last writer wins.
     *
     * A real thread pool, because `Dispatchers.Unconfined` runs each `async` to completion before starting
     * the next and nothing ever interleaves.
     */
    @Test
    fun `concurrent writes do not lose values`() =
        runTest(timeout = 30.seconds) {
            val subject = storage(cipher = CountingCipher(delayMillis = 50), dispatcher = Dispatchers.IO)
            (1..10).map { async(Dispatchers.IO) { subject.set("key-$it", "value-$it".toCharArray()) } }.awaitAll()

            (1..10).forEach {
                assertArrayEquals("value-$it was dropped", "value-$it".toCharArray(), subject.get("key-$it"))
            }
        }

    /**
     * Two instances over one file, which `create()` makes easy and the reopen test above shows is supported.
     * Per-instance locks let both read the old map and overwrite each other.
     */
    @Test
    fun `two instances over one file do not lose values`() =
        runTest(timeout = 30.seconds) {
            val file = File(folder.root, "store.json")
            val first = storage(file, CountingCipher(delayMillis = 50), Dispatchers.IO)
            val second = storage(file, CountingCipher(delayMillis = 50), Dispatchers.IO)

            listOf(
                async(Dispatchers.IO) { first.set("from-first", "1".toCharArray()) },
                async(Dispatchers.IO) { second.set("from-second", "2".toCharArray()) },
            ).awaitAll()

            assertArrayEquals("the first instance's value was lost", "1".toCharArray(), first.get("from-first"))
            assertArrayEquals("the second instance's value was lost", "2".toCharArray(), first.get("from-second"))
        }

    @Test
    fun `a corrupt file is reset rather than failing every call`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            file.writeText("{ this is not json")

            val subject = storage(file)
            assertNull(subject.get("refresh"))
            subject.set("refresh", "secret-value".toCharArray())
            assertArrayEquals("secret-value".toCharArray(), subject.get("refresh"))
        }

    /**
     * The reset must reach disk. Two reads with no write between: unpersisted, the corrupt file survives,
     * the second read reparses it and warns again, and the logged claim of a reset is false.
     */
    @Test
    fun `a corrupt file is reset on disk, not only in memory`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            file.writeText("{ this is not json")

            val subject = storage(file)
            assertNull(subject.get("refresh"))
            assertNull(subject.get("refresh"))

            assertEquals(
                "the reset should be persisted, so the warning fires once rather than per read",
                1,
                sink.records.count { it.message.contains("unreadable") },
            )
            assertEquals("the corrupt file should have been replaced", "{}", file.readText())
        }

    /**
     * A bad value drops that entry only. A tag failure says nothing about the key, and every entry shares
     * one, so destroying the rest would turn one damaged blob into total loss.
     */
    @Test
    fun `an unreadable value is discarded without touching the others`() =
        runTest(timeout = 5.seconds) {
            val cipher = CountingCipher()
            val subject = storage(cipher = cipher)
            subject.set("damaged", "one".toCharArray())
            subject.set("intact", "two".toCharArray())

            cipher.failNextDecrypt = SecureStorageException.ValueUnreadable()
            val thrown = runCatching { subject.get("damaged") }.exceptionOrNull()

            assertTrue("expected ValueUnreadable, got $thrown", thrown is SecureStorageException.ValueUnreadable)
            assertNull("the damaged entry should be gone", subject.get("damaged"))
            assertArrayEquals("the intact entry was destroyed", "two".toCharArray(), subject.get("intact"))
        }

    /**
     * A lost key clears the whole store, because every remaining blob was sealed under it. Keeping them
     * would fail every later read and let the next write mix a fresh key with stale ciphertext.
     */
    @Test
    fun `key invalidation clears the whole store and propagates`() =
        runTest(timeout = 5.seconds) {
            val cipher = CountingCipher()
            val subject = storage(cipher = cipher)
            subject.set("first", "one".toCharArray())
            subject.set("second", "two".toCharArray())

            cipher.failNextDecrypt = SecureStorageException.KeyInvalidated()
            val thrown = runCatching { subject.get("first") }.exceptionOrNull()

            assertTrue("expected KeyInvalidated, got $thrown", thrown is SecureStorageException.KeyInvalidated)
            assertNull(subject.get("first"))
            assertNull("the whole store should have been cleared", subject.get("second"))
        }

    /**
     * The plaintext the cipher was handed must be wiped once `set` returns.
     *
     * Observable because the cipher keeps the reference it was given: if storage did not overwrite the
     * buffer, the secret would still be readable through it. This is the whole reason values are
     * `CharArray` rather than `String`, so it is the assertion that proves the change was worth making.
     */
    @Test
    fun `the plaintext handed to the cipher is wiped after set`() =
        runTest(timeout = 5.seconds) {
            var handed: ByteArray? = null
            val capturing =
                object : ValueCipher {
                    override fun encrypt(
                        aad: String,
                        plaintext: ByteArray,
                    ): String {
                        handed = plaintext
                        return "blob"
                    }

                    override fun decrypt(
                        aad: String,
                        blob: String,
                    ): ByteArray = ByteArray(0)
                }

            storage(cipher = capturing).set("refresh", "secret-value".toCharArray())

            assertArrayEquals(
                "the plaintext buffer was left readable after the write",
                ByteArray(handed!!.size),
                handed,
            )
        }

    /** The caller's array is theirs: clearing it would be a surprising side effect. */
    @Test
    fun `set does not clear the caller's array`() =
        runTest(timeout = 5.seconds) {
            val value = "secret-value".toCharArray()
            storage().set("refresh", value)

            assertArrayEquals("secret-value".toCharArray(), value)
        }

    /** A crash-safe write leaves no debris, so the directory does not fill with temp files. */
    @Test
    fun `no temporary files are left behind`() =
        runTest(timeout = 5.seconds) {
            val subject = storage()
            subject.set("a", "1".toCharArray())
            subject.set("b", "2".toCharArray())

            val leftovers =
                folder.root
                    .listFiles()
                    ?.filter { it.name.endsWith(".tmp") }
                    .orEmpty()
            assertTrue("temp files left behind: $leftovers", leftovers.isEmpty())
        }
}
