package com.payabli.sdk.core.storage.impl

import com.payabli.sdk.core.storage.SecureStorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The single identity every consumer derives from: the lock, the Keystore alias, the temporary-file prefix.
 *
 * Testable on the JVM although none of its consumers are, because `MessageDigest` is `java.security` rather than
 * a platform class. That is the reason it lives in `impl` and not in `platform`.
 */
class StoreIdentityTest {
    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    /**
     * The failure that put this class here. Deriving from the file name alone, two stores in different
     * directories shared one Keystore alias while holding separate locks, so either could replace the key the
     * other's ciphertext was sealed under.
     */
    @Test
    fun `equal file names in different directories are different stores`() {
        val first = File(folder.newFolder("a"), "store.json")
        val second = File(folder.newFolder("b"), "store.json")

        assertNotEquals(
            "two directories with the same file name collapsed to one identity",
            StoreIdentity.of(first),
            StoreIdentity.of(second),
        )
    }

    /** And the converse, which is what canonicalisation buys: one file is one store however it is spelled. */
    @Test
    fun `two spellings of one path are the same store`() {
        val directory = folder.newFolder("nested")
        val plain = File(directory, "store.json")
        val roundabout = File(directory, "../nested/store.json")

        assertEquals(
            "the same file read as two identities, which would mint it a second key",
            StoreIdentity.of(plain),
            StoreIdentity.of(roundabout),
        )
    }

    @Test
    fun `different file names in one directory are different stores`() {
        assertNotEquals(
            StoreIdentity.of(File(folder.root, "a.json")),
            StoreIdentity.of(File(folder.root, "b.json")),
        )
    }

    /**
     * Fixed width, which is what makes no prefix a prefix of another and keeps the temp name bounded.
     *
     * Asserted rather than assumed, because both properties read as obvious and neither survives a change to
     * the derivation that nobody rechecks.
     */
    @Test
    fun `an identity is fixed-width lowercase hex whatever the name`() {
        val short = StoreIdentity.of(File(folder.root, "a"))
        val long = StoreIdentity.of(File(folder.root, "x".repeat(200) + ".json"))

        assertEquals(StoreIdentity.IDENTITY_BYTES * 2, short.length)
        assertEquals("a 200-character name changed the identity's width", short.length, long.length)
        assertTrue("not lowercase hex: $short", short.all { it in '0'..'9' || it in 'a'..'f' })
    }

    /**
     * An unresolvable path fails rather than answering with a different representation.
     *
     * The fallback this replaces returned the absolute path when canonicalisation failed, which gave one store two
     * identities for as long as the failure lasted. Since the alias, the lock and the temp prefix all derive from
     * this, a later successful resolution would look for another key, take another lock, and stop recognising its
     * own temp files.
     */
    @Test
    fun `an unresolvable path fails rather than falling back`() {
        val unresolvable =
            object : File(folder.root, "store.json") {
                override fun getCanonicalPath(): String = throw IOException("cannot resolve")
            }

        val thrown = runCatching { StoreIdentity.of(unresolvable) }.exceptionOrNull()

        assertTrue("expected StorageUnavailable, got $thrown", thrown is SecureStorageException.StorageUnavailable)
    }

    /** Stable across calls, or a store would lose its key between one write and the next. */
    @Test
    fun `an identity is stable across calls`() {
        val file = File(folder.root, "store.json")

        assertEquals(StoreIdentity.of(file), StoreIdentity.of(file))
    }
}
