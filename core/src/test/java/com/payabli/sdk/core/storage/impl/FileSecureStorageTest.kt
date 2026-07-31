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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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
import java.io.IOException
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
            // Not a well-formed envelope, which the real cipher reports the same way for a blob that fails base64
            // decoding or is shorter than an IV plus a tag. Without this the double threw IndexOutOfBounds, a
            // failure the contract does not have, so a test feeding it a bad blob learned nothing about storage.
            if (parts.size < 3) throw SecureStorageException.StorageUnavailable()
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
     *
     * **Already a deterministic detector, and measured to be one.** With the lock neutralised, so that each call
     * takes its own `Mutex`, this fails 5 runs in 5: ten writers overlap on ten threads, all read the same map, and
     * the file ends with a single entry. `delayMillis` is what buys that, holding each writer inside the window
     * while the loop dispatches the rest.
     *
     * A `CountDownLatch` start gate was tried and reverted, because it detected no better and the machinery was not
     * free. A barrier inside `encrypt` would be worse than useless: it deadlocks the correct implementation, since
     * the first writer holds the lock while waiting for writers that cannot enter it.
     */
    @Test
    fun `concurrent writes do not lose values`() =
        runTest(timeout = 30.seconds) {
            val subject = storage(cipher = CountingCipher(delayMillis = 50), dispatcher = Dispatchers.IO)
            (1..WRITERS)
                .map { index ->
                    async(Dispatchers.IO) { subject.set("key-$index", "value-$index".toByteArray()) }
                }.awaitAll()

            (1..WRITERS).forEach {
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
     * The caller's array reaches the cipher unchanged and uncopied.
     *
     * The inverse of what this test used to assert. It once checked that storage *wiped* the buffer it handed the
     * cipher, which was necessary because the old `CharArray` contract encoded into a second array that storage
     * then owned. Bytes go straight through, so no such buffer exists: nothing of ours holds the plaintext, and
     * there is nothing for storage to forget to clear. Wiping is the caller's, as the contract now says.
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
     * Two names that collapse under UTF-8 must not be able to open each other's value.
     *
     * `"\uD800"` and `"\uD801"` both encode to the single byte `0x3f`, measured, so as GCM AAD they are the same
     * name. Since every entry shares one key, the AAD is the only thing binding a blob to its entry, and two
     * entries with one AAD is the substitution attack the binding exists to stop, reached by naming rather than
     * by editing the file. Rejecting the names makes the collision unreachable, so this asserts through the
     * public surface rather than reaching for the cipher.
     */
    @Test
    fun `names that collide under UTF-8 cannot open each other's value`() =
        runTest(timeout = 5.seconds) {
            val subject = storage()

            val first = runCatching { subject.set("\uD800", "first".toByteArray()) }.exceptionOrNull()
            val second = runCatching { subject.set("\uD801", "second".toByteArray()) }.exceptionOrNull()

            assertTrue("a name that cannot round-trip was accepted, got $first", first is IllegalArgumentException)
            assertTrue("a name that cannot round-trip was accepted, got $second", second is IllegalArgumentException)
            // And nothing was stored under the name they would both have collapsed to.
            assertNull("a rejected write reached the store anyway", subject.get("?"))
        }

    /** Every entry point checks, because one unguarded door reopens the collision above. */
    @Test
    fun `an unrepresentable key is rejected by get, set and remove`() =
        runTest(timeout = 5.seconds) {
            val subject = storage()
            val malformed = "\uDC00-trailing-surrogate"

            val onGet = runCatching { subject.get(malformed) }.exceptionOrNull()
            val onSet = runCatching { subject.set(malformed, "x".toByteArray()) }.exceptionOrNull()
            val onRemove = runCatching { subject.remove(malformed) }.exceptionOrNull()

            assertTrue("get accepted it, got $onGet", onGet is IllegalArgumentException)
            assertTrue("set accepted it, got $onSet", onSet is IllegalArgumentException)
            assertTrue("remove accepted it, got $onRemove", onRemove is IllegalArgumentException)
        }

    /**
     * The guard rejects only what is malformed.
     *
     * A supplementary character is a *valid* surrogate pair and must be accepted. A check that refused anything
     * containing a surrogate code unit would pass the two tests above while breaking every legitimate name
     * outside the basic plane, which is the way this fix would plausibly go wrong.
     */
    @Test
    fun `a key containing a valid supplementary character is accepted`() =
        runTest(timeout = 5.seconds) {
            val subject = storage()
            val astral = "refresh-😀"

            subject.set(astral, "secret-value".toByteArray())

            assertArrayEquals("secret-value".toByteArray(), subject.get(astral))
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
     * `remove` of a key that is not in the store must still reclaim an orphan.
     *
     * The sequence: a `set` is interrupted after the temp file is flushed and before the rename, so the orphan
     * holds ciphertext for an entry that never reached the store file. A later `remove` of that entry reads a map
     * without it, and if the sweep only ran inside `write` it would skip both, report success, and leave the blob
     * on disk decryptable under the same alias. Deletion is the one promise `remove` makes.
     */
    @Test
    fun `remove of an absent key still reclaims an orphaned temporary file`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            val subject = storage(file)
            subject.set("present", "value".toByteArray())
            val orphan =
                File(folder.root, "pbl${StoreIdentity.of(file)}.4242.tmp")
                    .apply { writeText("""{"interrupted":"ciphertext"}""") }

            subject.remove("never-written")

            assertFalse("remove reported success with an orphan still on disk", orphan.exists())
        }

    /** And the write path keeps sweeping, so the fix added a path rather than moving one. */
    @Test
    fun `remove of a present key still reclaims an orphaned temporary file`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            val subject = storage(file)
            subject.set("present", "value".toByteArray())
            val orphan =
                File(folder.root, "pbl${StoreIdentity.of(file)}.4243.tmp")
                    .apply { writeText("""{"interrupted":"ciphertext"}""") }

            subject.remove("present")

            assertFalse("the write path stopped sweeping", orphan.exists())
            assertNull(subject.get("present"))
        }

    /**
     * A malformed blob raises `StorageUnavailable`, and the message must name that cause.
     *
     * The file was read perfectly well here, so a message naming only the file would send a reader looking for a
     * disk problem that did not happen.
     */
    @Test
    fun `a malformed value reports storage unavailable with a message naming the value`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            val subject = storage(file)
            subject.set("refresh", "secret-value".toByteArray())
            // A blob the fake cipher cannot open: no AAD delimiter at all.
            file.writeText("""{"refresh":"not-a-blob"}""")

            val thrown = runCatching { subject.get("refresh") }.exceptionOrNull()

            assertTrue("expected a storage failure, got $thrown", thrown is SecureStorageException)
            assertTrue(
                "the message names only the file: ${thrown?.message}",
                thrown?.message?.contains("malformed") == true,
            )
        }

    /**
     * A store on an unresolvable path fails at construction, before any write.
     *
     * The identity is resolved once, in the initialiser, precisely so a path that cannot be resolved is a failure
     * rather than a second identity discovered later. Failing here also means no caller can hold a store whose
     * alias, lock and temp prefix might disagree.
     */
    @Test
    fun `constructing a store on an unresolvable path fails`() {
        val unresolvable =
            object : File(folder.root, "store.json") {
                override fun getCanonicalPath(): String = throw IOException("cannot resolve")
            }

        val thrown = runCatching { storage(unresolvable) }.exceptionOrNull()

        assertTrue("expected StorageUnavailable, got $thrown", thrown is SecureStorageException.StorageUnavailable)
    }

    /**
     * The identity is resolved once, so a resolution that changes later cannot split one store in two.
     *
     * Modelled with a path whose `canonicalPath` answers differently on the second call, which is the hazard the
     * removed fallback created: the alias, the lock and the temp prefix all derive from this, so a store that
     * re-resolves can look for another key, take another lock, and stop recognising its own temp files. Only the
     * identity shifts here; the file I/O still uses the real path, so the store keeps working and the split is what
     * the test observes.
     */
    @Test
    fun `the identity is resolved once per store`() =
        runTest(timeout = 5.seconds) {
            val real = File(folder.root, "store.json")
            var resolutions = 0
            val shifting =
                object : File(real.path) {
                    override fun getCanonicalPath(): String {
                        resolutions++
                        return if (resolutions == 1) real.canonicalPath else real.canonicalPath + "-moved"
                    }
                }

            val subject = storage(shifting)
            subject.set("a", "1".toByteArray())
            // Planted under the prefix the first resolution produced, which is the only one a correct store uses.
            val planted =
                File(folder.root, "pbl${StoreIdentity.of(real)}.4242.tmp").apply { writeText("stale") }
            subject.set("b", "2".toByteArray())

            assertFalse(
                "a later resolution produced a different prefix, so the sweep missed its own temp",
                planted.exists(),
            )
            assertTrue("canonicalPath was never re-read, so this proves nothing", resolutions >= 1)
        }

    /**
     * A sibling whose name overlaps the prefix and the suffix must not break the store.
     *
     * `<prefix>tmp` satisfies both `startsWith` and `endsWith`, because the prefix ends with the delimiter and the
     * suffix begins with one, while being shorter than the two combined. The slice then ran backwards and threw
     * `StringIndexOutOfBoundsException` out of the sweep, past `write()`'s handlers, so **every** write and every
     * absent-key `remove` failed while such a file existed. The store never produces that name, so it takes a
     * host-app or leftover file, which makes it an improbable trigger with a total consequence.
     */
    @Test
    fun `a sibling overlapping the prefix and suffix does not break writes`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            val subject = storage(file)
            val overlapping =
                File(folder.root, "pbl${StoreIdentity.of(file)}.tmp").apply { writeText("not ours") }

            subject.set("refresh", "secret-value".toByteArray())
            subject.remove("never-written")

            assertArrayEquals("secret-value".toByteArray(), subject.get("refresh"))
            assertTrue("a file the store never created was deleted", overlapping.exists())
        }

    /**
     * A sweep that cannot delete an orphan must say so, or `remove` lies again.
     *
     * The whole point of sweeping on `remove` is that deletion means deletion. `delete()` returning false used to
     * be simply not the `if` branch, so the caller was told the entry was gone while a decryptable blob for it was
     * still on disk. Injected by taking write permission off the directory, which is a real cause rather than a
     * stub, with the orphan already present.
     */
    @Test
    fun `remove reports failure when an orphan cannot be deleted`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            val subject = storage(file)
            subject.set("present", "value".toByteArray())
            File(folder.root, "pbl${StoreIdentity.of(file)}.4242.tmp").writeText("""{"interrupted":"ciphertext"}""")

            assertTrue("the directory stayed writable, so nothing was tested", folder.root.setWritable(false))
            val thrown =
                try {
                    runCatching { subject.remove("never-written") }.exceptionOrNull()
                } finally {
                    folder.root.setWritable(true)
                }

            assertTrue("expected StorageUnavailable, got $thrown", thrown is SecureStorageException.StorageUnavailable)
        }

    /**
     * A directory that exists but cannot be listed is a failure, not an empty directory.
     *
     * `listFiles` answers null for both a missing parent and a listing failure, and treating them alike meant
     * `remove` reported success without ever having looked. Injected by removing read permission from the
     * directory: measured, that leaves `isDirectory` true with `listFiles` null, while a known path inside stays
     * readable, so the store still loads and only the sweep is blinded.
     */
    @Test
    fun `remove reports failure when the directory cannot be listed`() =
        runTest(timeout = 5.seconds) {
            val file = File(folder.root, "store.json")
            val subject = storage(file)
            subject.set("present", "value".toByteArray())

            assertTrue("the directory stayed readable, so nothing was tested", folder.root.setReadable(false))
            val thrown =
                try {
                    runCatching { subject.remove("never-written") }.exceptionOrNull()
                } finally {
                    folder.root.setReadable(true)
                }

            assertTrue("expected StorageUnavailable, got $thrown", thrown is SecureStorageException.StorageUnavailable)
        }

    /** And an absent directory is nothing to sweep rather than a failure, so absence did not become an error. */
    @Test
    fun `remove succeeds when the store's directory does not exist`() =
        runTest(timeout = 5.seconds) {
            val subject = storage(File(folder.root, "absent/store.json"))

            subject.remove("never-written")
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
     * The premise the narrowed catch rests on: malformed JSON is a `SerializationException`.
     *
     * `read` catches that rather than its `IllegalArgumentException` supertype, because the handler does not
     * rethrow, it overwrites the store with an empty map. Catching the supertype turns a programming error raised
     * from inside a serializer into data loss. Asserted against the library directly, so it pins kotlinx's
     * behaviour rather than ours: if malformed input ever stopped being a `SerializationException`, the corrupt-file
     * path would silently stop resetting.
     */
    @Test
    fun `malformed json throws a serialization exception rather than a bare argument exception`() {
        val thrown =
            runCatching {
                Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), "{ this is not json")
            }.exceptionOrNull()

        assertTrue("expected SerializationException, got $thrown", thrown is SerializationException)
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

    private companion object {
        /** Ten writers, which is enough: measured, a removed lock loses nine of them on every run. */
        const val WRITERS = 10
    }
}
