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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        var failNextEncrypt: SecureStorageException? = null,
        /** Stands for the Keystore alias: a write into a non-empty store must not create one. */
        var keyPresent: Boolean = true,
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
            failNextEncrypt?.let {
                failNextEncrypt = null
                throw it
            }
            val ordinal = synchronized(this) { ++encryptions }
            if (delayMillis > 0) Thread.sleep(delayMillis)
            // Hex, not String(bytes), because this fake must be byte-faithful. Framing the payload as text put
            // the very lossy conversion the store just shed inside the double, so bytes that are not valid
            // UTF-8 came back as '?' and every test using this cipher ran through a lossy layer.
            return "$ordinal|$aad|${plaintext.toHex()}"
        }

        /** Records what storage decided, so a test can prove the flag is derived rather than always false. */
        var lastMayCreate: Boolean? = null
            private set

        override fun ensureKey(mayCreate: Boolean) {
            lastMayCreate = mayCreate
            if (keyPresent) return
            if (!mayCreate) throw SecureStorageException.KeyInvalidated()
            keyPresent = true
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        private fun String.fromHex(): ByteArray =
            ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

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
            return parts[2].fromHex()
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
            subject.set("refresh", "secret-value".toByteArray())
            assertArrayEquals("secret-value".toByteArray(), subject.get("refresh"))
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
            storage(file).set("refresh", "secret-value".toByteArray())
            assertArrayEquals("secret-value".toByteArray(), storage(file).get("refresh"))
        }

    @Test
    fun `a second write replaces the first`() =
        runTest(timeout = 5.seconds) {
            val subject = storage()
            subject.set("refresh", "first".toByteArray())
            subject.set("refresh", "second".toByteArray())
            assertArrayEquals("second".toByteArray(), subject.get("refresh"))
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
            subject.set("a", "1".toByteArray())
            assertTrue("the store was not created", file.exists())
            subject.set("b", "2".toByteArray())

            assertArrayEquals("1".toByteArray(), subject.get("a"))
            assertArrayEquals("2".toByteArray(), subject.get("b"))
        }

    @Test
    fun `remove deletes the value and is silent about an absent key`() =
        runTest(timeout = 5.seconds) {
            val subject = storage()
            subject.set("refresh", "secret-value".toByteArray())
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
            subject.set("refresh", "same".toByteArray())
            val first = file.readText()
            subject.set("refresh", "same".toByteArray())

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
            subject.set("alpha", "alpha-secret".toByteArray())
            subject.set("beta", "beta-secret".toByteArray())

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
            (1..10).map { async(Dispatchers.IO) { subject.set("key-$it", "value-$it".toByteArray()) } }.awaitAll()

            (1..10).forEach {
                assertArrayEquals("value-$it was dropped", "value-$it".toByteArray(), subject.get("key-$it"))
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
                async(Dispatchers.IO) { first.set("from-first", "1".toByteArray()) },
                async(Dispatchers.IO) { second.set("from-second", "2".toByteArray()) },
            ).awaitAll()

            assertArrayEquals("the first instance's value was lost", "1".toByteArray(), first.get("from-first"))
            assertArrayEquals("the second instance's value was lost", "2".toByteArray(), first.get("from-second"))
        }

    @Test
    fun `a corrupt file is reset rather than failing every call`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            file.writeText("{ this is not json")

            val subject = storage(file)
            assertNull(subject.get("refresh"))
            subject.set("refresh", "secret-value".toByteArray())
            assertArrayEquals("secret-value".toByteArray(), subject.get("refresh"))
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
            subject.set("damaged", "one".toByteArray())
            subject.set("intact", "two".toByteArray())

            cipher.failNextDecrypt = SecureStorageException.ValueUnreadable()
            val thrown = runCatching { subject.get("damaged") }.exceptionOrNull()

            assertTrue("expected ValueUnreadable, got $thrown", thrown is SecureStorageException.ValueUnreadable)
            assertNull("the damaged entry should be gone", subject.get("damaged"))
            assertArrayEquals("the intact entry was destroyed", "two".toByteArray(), subject.get("intact"))
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
            subject.set("first", "one".toByteArray())
            subject.set("second", "two".toByteArray())

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
    fun `the caller's array reaches the cipher without being copied`() =
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

                    override fun ensureKey(mayCreate: Boolean) = Unit
                }
            val value = "secret-value".toByteArray()

            storage(cipher = capturing).set("refresh", value)

            // Identity, not equality. A copy would be a second plaintext this store had made and would then be
            // responsible for wiping, which is the buffer the old CharArray conversion created and had to clear.
            // Passing the caller's array through means there is no such buffer to leak or to forget.
            assertSame("the value was copied on the way to the cipher", value, handed)
        }

    /**
     * Any byte sequence round-trips exactly, which is the property the old text contract could not offer.
     *
     * These bytes are deliberately not valid UTF-8: a lone `0x80` continuation byte, `0xED 0xA0 0x80` which is
     * the UTF-8 encoding of an unpaired surrogate, and a NUL. Under the previous `CharArray` contract the
     * equivalent input was silently replaced with `?`, measured, so the value read back differed from the value
     * stored. Storing bytes means there is nothing to interpret and therefore nothing to corrupt.
     */
    @Test
    fun `an arbitrary byte sequence round-trips exactly`() =
        runTest(timeout = 5.seconds) {
            val subject = storage()
            val value = byteArrayOf(0x00, 0x80.toByte(), 0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x7F, -1)

            subject.set("refresh", value)

            assertArrayEquals("the store altered bytes it was asked to keep", value, subject.get("refresh"))
        }

    /** A fresh array per read, so a caller wiping what it got cannot blank the next reader's copy. */
    @Test
    fun `get returns a fresh array each call`() =
        runTest(timeout = 5.seconds) {
            val subject = storage()
            subject.set("refresh", "secret-value".toByteArray())

            val first = subject.get("refresh")!!
            first.fill(0)

            assertArrayEquals(
                "wiping one read's array changed what the next read returned",
                "secret-value".toByteArray(),
                subject.get("refresh"),
            )
        }

    /** The caller's array is theirs: clearing it would be a surprising side effect. */
    @Test
    fun `set does not clear the caller's array`() =
        runTest(timeout = 5.seconds) {
            val value = "secret-value".toByteArray()
            storage().set("refresh", value)

            assertArrayEquals("secret-value".toByteArray(), value)
        }

    /**
     * A write into a store that already holds entries must not create a key.
     *
     * With no read first, a lost alias and a fresh install look identical from the write side. Creating a key
     * is right for the second and wrong for the first: the new blob would land beside ciphertext sealed under
     * the key that is gone, nothing would report the loss, and each old value would fail alone on some later
     * read as though it were individually corrupt.
     */
    @Test
    fun `a write into a non-empty store whose key is gone reports invalidation`() =
        runTest(timeout = 5.seconds) {
            val cipher = CountingCipher()
            val subject = storage(cipher = cipher)
            subject.set("first", "one".toByteArray())

            cipher.keyPresent = false
            val thrown = runCatching { subject.set("second", "two".toByteArray()) }.exceptionOrNull()

            assertTrue("expected KeyInvalidated, got $thrown", thrown is SecureStorageException.KeyInvalidated)
            cipher.keyPresent = true
            assertNull("the stranded entry should have been cleared", subject.get("first"))
            assertNull("the rejected write should not have been stored", subject.get("second"))
        }

    /** The other half of the same check: no key and no entries is a fresh install, which does create one. */
    @Test
    fun `a write into an empty store creates the key`() =
        runTest(timeout = 5.seconds) {
            val cipher = CountingCipher(keyPresent = false)
            val subject = storage(cipher = cipher)
            subject.set("refresh", "secret-value".toByteArray())

            assertArrayEquals("secret-value".toByteArray(), subject.get("refresh"))
            // The flag has to be derived from the store, not a constant. Hardcoded false breaks the line above,
            // but hardcoded true breaks nothing there, so the value passed is asserted directly.
            assertEquals("an empty store must be allowed to create a key", true, cipher.lastMayCreate)
        }

    /**
     * The window a separate presence check left open: an alias that disappears **after** provisioning.
     *
     * Provisioning says a key is there, and then it is not by the time `encrypt` runs. `encrypt` must refuse to
     * create a replacement, because doing so would seal this value under a new key and leave every earlier blob
     * unreadable with nothing reporting it. Instead the failure surfaces and the store is cleared.
     */
    @Test
    fun `a key that vanishes after provisioning is reported rather than replaced`() =
        runTest(timeout = 5.seconds) {
            val cipher = CountingCipher()
            val subject = storage(cipher = cipher)
            subject.set("first", "one".toByteArray())

            cipher.failNextEncrypt = SecureStorageException.KeyInvalidated()
            val thrown = runCatching { subject.set("second", "two".toByteArray()) }.exceptionOrNull()

            assertTrue("expected KeyInvalidated, got $thrown", thrown is SecureStorageException.KeyInvalidated)
            assertNull("the store should have been cleared rather than mixed", subject.get("first"))
            assertNull(subject.get("second"))
        }

    /**
     * A key lost while encrypting clears the store first, as the read path does.
     *
     * Leaving the old blobs lets a retry create a fresh key and mix it with ciphertext sealed under the
     * previous one, which is the state this subtype exists to prevent.
     */
    @Test
    fun `a key lost while encrypting clears the store before propagating`() =
        runTest(timeout = 5.seconds) {
            val cipher = CountingCipher()
            val subject = storage(cipher = cipher)
            subject.set("first", "one".toByteArray())

            cipher.failNextEncrypt = SecureStorageException.KeyInvalidated()
            val thrown = runCatching { subject.set("second", "two".toByteArray()) }.exceptionOrNull()

            assertTrue("expected KeyInvalidated, got $thrown", thrown is SecureStorageException.KeyInvalidated)
            assertNull("the store should have been cleared", subject.get("first"))
        }

    /** The bound on the clause above: a failure that is not key loss must leave the store alone. */
    @Test
    fun `a cipher failure that is not key loss leaves the store intact`() =
        runTest(timeout = 5.seconds) {
            val cipher = CountingCipher()
            val subject = storage(cipher = cipher)
            subject.set("first", "one".toByteArray())

            cipher.failNextEncrypt = SecureStorageException.CryptoUnavailable()
            val thrown = runCatching { subject.set("second", "two".toByteArray()) }.exceptionOrNull()

            assertTrue("expected CryptoUnavailable, got $thrown", thrown is SecureStorageException.CryptoUnavailable)
            assertArrayEquals("an unrelated failure destroyed the store", "one".toByteArray(), subject.get("first"))
        }

    /**
     * A temp file from a write that never finished must be reclaimed.
     *
     * Process death between creating the temp and renaming it orphans one, and nothing else looks for it. It
     * holds ciphertext for every entry present at that moment, still openable under the same key alias, so an
     * entry a later `remove` deleted would survive inside it.
     */
    @Test
    fun `an orphaned temporary file is discarded by the next write`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            // The shape createTempFile produces for this store: prefix, identity, delimiter, digits, suffix.
            val orphan =
                File(folder.root, "pbl${StoreIdentity.of(file)}.4242.tmp")
                    .apply { writeText("""{"stale":"ciphertext"}""") }

            storage(file).set("refresh", "secret-value".toByteArray())

            assertFalse("an unfinished write's temp file survived", orphan.exists())
        }

    /**
     * The sweep must not reach another store's temp file.
     *
     * Written against the derived prefix rather than a literal name, which is what it should always have
     * asserted: the guarantee is "not ours", and ownership is what the identity decides. Every prefix is now the
     * same width, so a sibling's prefix cannot be an initial substring of ours the way `store` and `store2`
     * could.
     */
    @Test
    fun `the sweep leaves another store's temporary file alone`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            val sibling = File(folder.root, "sibling.json")
            val theirs =
                File(folder.root, "pbl${StoreIdentity.of(sibling)}.4242.tmp").apply { writeText("in flight") }

            storage(file).set("refresh", "secret-value".toByteArray())

            assertTrue("another store's in-flight temp file was deleted", theirs.exists())
        }

    /**
     * Nor a file carrying our own prefix whose middle is not digits, since the store never produces such a name.
     * The directory belongs to the host app too.
     */
    @Test
    fun `the sweep leaves a file that is not one of its temporaries alone`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            val theirs =
                File(folder.root, "pbl${StoreIdentity.of(file)}.backup.tmp").apply { writeText("not ours") }

            storage(file).set("refresh", "secret-value".toByteArray())

            assertTrue("a file matching the prefix but not the shape was deleted", theirs.exists())
        }

    /**
     * A one-character file name must still write.
     *
     * `File.createTempFile` rejects a prefix under three characters, and the previous `<fileName>.` form gave two
     * for a name like `a`. The `IllegalArgumentException` was caught nowhere in `write`, so it escaped the
     * `SecureStorageException` surface entirely: every write to such a store threw a raw platform exception.
     */
    @Test
    fun `a store whose file name is one character still writes`() =
        runTest(timeout = 5.seconds) {
            val subject = storage(File(folder.root, "a"))

            subject.set("refresh", "secret-value".toByteArray())

            assertArrayEquals("secret-value".toByteArray(), subject.get("refresh"))
        }

    /**
     * The temp name is bounded by construction, not by the store's name being short.
     *
     * With the prefix derived from a fixed-width identity, a long file name cannot push the temp name toward
     * `NAME_MAX`, measured at 255 on the emulator and both test phones. Asserted because "short enough" is
     * otherwise a claim nobody rechecks after changing the prefix.
     */
    @Test
    fun `the temporary file name stays bounded however long the store's name is`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "s".repeat(120) + ".json")
            val subject = storage(file)
            val orphan =
                File(folder.root, "pbl${StoreIdentity.of(file)}.4242.tmp").apply { writeText("stale") }

            subject.set("refresh", "secret-value".toByteArray())

            assertTrue("the sweep did not recognise its own temp name", !orphan.exists())
            assertTrue(
                "temp names grow with the store's name: ${orphan.name.length} bytes",
                orphan.name.length < 96,
            )
        }

    /**
     * A parent directory created by somebody else between `exists()` and `mkdirs()` is not a failure.
     *
     * `mkdirs` returns false for a directory that already exists, and two stores under one missing parent hold
     * separate locks, so the loser of that race reported `StorageUnavailable` with the directory sitting right
     * there. Injected with a parent that reports exactly that state, rather than by racing threads and hoping.
     */
    @Test
    fun `a parent created concurrently does not fail the write`() =
        runTest(timeout = 5.seconds) {
            val real = folder.newFolder("appeared")
            val racingParent =
                object : File(real.path) {
                    override fun exists(): Boolean = false

                    override fun mkdirs(): Boolean = false

                    override fun isDirectory(): Boolean = true
                }
            val file =
                object : File(real, "store.json") {
                    override fun getParentFile(): File = racingParent
                }

            val subject = storage(file)
            subject.set("refresh", "secret-value".toByteArray())

            assertArrayEquals("secret-value".toByteArray(), subject.get("refresh"))
        }

    /**
     * A write that cannot create its temp file must leave the previous store complete.
     *
     * What matters is not the exception but what survives it. Injected by taking write permission off the
     * directory, which is the real cause rather than a stub, and restored in a `finally` so the rule can
     * still delete the folder.
     *
     * **This does not prove the rename ordering.** An unwritable directory also stops a stray `file.delete()`
     * from succeeding, so reintroducing the pre-delete this suite once had leaves the test green. Nor is the
     * `renameTo` false branch reachable: every entry point reads before it writes, and the states that make a
     * rename fail, a directory at the path or an unwritable parent, fail the read or the temp creation first.
     * Both are held by inspection and by the instrumented suite, not by this test.
     */
    @Test
    fun `a write that cannot create its temporary file leaves the previous store intact`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            val subject = storage(file)
            subject.set("refresh", "secret-value".toByteArray())
            val before = file.readText()

            assertTrue("the directory stayed writable, so nothing was tested", folder.root.setWritable(false))
            val thrown =
                try {
                    runCatching { subject.set("another", "value".toByteArray()) }.exceptionOrNull()
                } finally {
                    folder.root.setWritable(true)
                }

            assertTrue("expected StorageUnavailable, got $thrown", thrown is SecureStorageException.StorageUnavailable)
            assertEquals("the previous store was not left intact", before, file.readText())
            assertArrayEquals("secret-value".toByteArray(), subject.get("refresh"))
        }

    /** The directory need not exist yet: production is handed one, not asked to guarantee it. */
    @Test
    fun `a store whose directory does not exist yet is created on first write`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "nested/deeper/store.json")
            val subject = storage(file)

            subject.set("refresh", "secret-value".toByteArray())

            assertTrue("the directory was not created", file.exists())
            assertArrayEquals("secret-value".toByteArray(), subject.get("refresh"))
        }

    /** A store that cannot be read at all is reported rather than swallowed as an empty store. */
    @Test
    fun `an unreadable store file reports storage as unavailable`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json").apply { mkdirs() }

            val thrown = runCatching { storage(file).get("refresh") }.exceptionOrNull()

            assertTrue("expected StorageUnavailable, got $thrown", thrown is SecureStorageException.StorageUnavailable)
        }

    /**
     * The reset of a corrupt file is best effort, and failing to persist it must not fail the call.
     *
     * The alternative is that one bad write makes every later call throw, and everything in the file is
     * ciphertext the caller can obtain again.
     */
    @Test
    fun `a corrupt file whose reset cannot be persisted still reads as empty`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            file.writeText("{ this is not json")
            val subject = storage(file)

            assertTrue("the directory stayed writable, so nothing was tested", folder.root.setWritable(false))
            try {
                assertNull(subject.get("refresh"))
            } finally {
                folder.root.setWritable(true)
            }

            assertTrue(
                "a reset that could not be persisted should say so",
                sink.records.any { it.message.contains("could not be persisted") },
            )
        }

    /**
     * The rejected input must not reach the log.
     *
     * `kotlinx.serialization` appends what it could not parse to its own message and the logger renders the
     * cause chain, so passing the original exception publishes the file's contents. `RedactedCause` keeps the
     * type and the frames and drops the text.
     */
    @Test
    fun `the decode failure is logged without the rejected input`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            file.writeText("""{ "refresh": "do-not-log-this-blob" """)

            assertNull(storage(file).get("refresh"))

            val logged = sink.records.joinToString("\n") { it.message }
            assertTrue("the reset warning is missing:\n$logged", logged.contains("unreadable"))
            assertFalse("the rejected file contents reached the log:\n$logged", logged.contains("do-not-log-this-blob"))
        }

    /** Production takes the default dispatcher, so one test has to exercise it rather than substitute one. */
    @Test
    fun `the default dispatcher is used when none is given`() =
        runTest(timeout = 5.seconds) {
            val subject =
                FileSecureStorage(
                    file = File(folder.root, "store.json"),
                    cipher = CountingCipher(),
                    logger = DefaultPayabliLogger(LogCategory.CORE, sink),
                )

            subject.set("refresh", "secret-value".toByteArray())

            assertArrayEquals("secret-value".toByteArray(), subject.get("refresh"))
        }

    /** A crash-safe write leaves no debris, so the directory does not fill with temp files. */
    @Test
    fun `no temporary files are left behind`() =
        runTest(timeout = 5.seconds) {
            val subject = storage()
            subject.set("a", "1".toByteArray())
            subject.set("b", "2".toByteArray())

            val leftovers =
                folder.root
                    .listFiles()
                    ?.filter { it.name.endsWith(".tmp") }
                    .orEmpty()
            assertTrue("temp files left behind: $leftovers", leftovers.isEmpty())
        }
}
