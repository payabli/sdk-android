package com.payabli.sdk.core.storage.impl

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultPayabliLogger
import com.payabli.sdk.core.storage.SecureStorageException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import kotlin.time.Duration.Companion.seconds

/**
 * The real Android Keystore, which is the only part of secure storage a device is required for.
 *
 * The persistence half is covered on the JVM by `FileSecureStorageTest`; nothing here repeats it. What is
 * here cannot be shown off-device: an emulator or phone is the only place `AndroidKeyStore` exists, and the
 * per-write IV guarantee is a property of the platform's own randomized encryption rather than of our code.
 *
 * A unique alias per run, deleted afterwards, so a leftover key from a previous run cannot make a failing
 * implementation look like a passing one.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecureStorageInstrumentedTest {
    private val sink = RecordingLogSink()
    private val logger = DefaultPayabliLogger(LogCategory.CORE, sink)
    private lateinit var directory: File
    private lateinit var keyAlias: String

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // noBackupFilesDir is what production passes, so the test exercises the same directory semantics.
        directory = File(context.noBackupFilesDir, "secure-storage-test").apply { mkdirs() }
        keyAlias = "payabli-test-${System.nanoTime()}"
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias) }
    }

    /**
     * A **replaced** key, which is the realistic shape of rotation and the only path that exercises the
     * `AEADBadTagException` mapping.
     *
     * Distinct from deletion: the alias exists, so the read finds a key and gets as far as the GCM tag check,
     * which fails because the bytes were sealed under the previous key. The value is just as unrecoverable, so
     * the caller must be told the same thing. Without the mapping this reports `CryptoUnavailable`, meaning
     * transient, and a caller would retry forever instead of re-authenticating.
     */
    @Test
    fun aReplacedKeyAlsoReportsInvalidation() =
        runTest(timeout = 30.seconds) {
            val subject = storage()
            subject.set("refresh", "secret-value")

            // Delete and recreate under the same alias: from the read side the key simply changed.
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(keyAlias)
            KeystoreValueCipher(keyAlias, logger).encrypt("forces a new key under the same alias")

            val thrown = runCatching { subject.get("refresh") }.exceptionOrNull()
            assertTrue(
                "expected KeyInvalidated for a replaced key, got $thrown",
                thrown is SecureStorageException.KeyInvalidated,
            )
        }

    /**
     * One damaged entry must not take the others with it.
     *
     * Every value in this store shares a single key alias, so a tag failure that deletes the alias destroys
     * everything, not just the entry that failed. A truncated or corrupted blob is a realistic cause of a tag
     * failure and says nothing about the key, which is why reporting and key lifecycle are separate concerns.
     *
     * The corruption is applied to the file rather than through the API, because there is no legitimate way to
     * produce a bad blob: it models a partial write, a bit flip or a hand edit.
     */
    @Test
    fun aCorruptBlobDoesNotDestroyTheOtherEntries() =
        runTest(timeout = 30.seconds) {
            val file = File(directory, "store.json")
            val subject = storage()
            subject.set("damaged", "first-value")
            subject.set("intact", "second-value")

            // Flip the payload of one entry only, leaving its base64 valid so it reaches the tag check.
            val map = Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), file.readText())
            val damaged = map.getValue("damaged")
            val flipped = damaged.dropLast(4) + if (damaged.endsWith("AAAA")) "BBBB" else "AAAA"
            file.writeText(
                Json.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    map + ("damaged" to flipped),
                ),
            )

            val thrown = runCatching { subject.get("damaged") }.exceptionOrNull()
            assertTrue(
                "a corrupt blob should report invalidation, got $thrown",
                thrown is SecureStorageException.KeyInvalidated,
            )

            assertEquals(
                "the other entry was destroyed by a failure that had nothing to do with it",
                "second-value",
                subject.get("intact"),
            )
        }

    private fun storage() =
        FileSecureStorage(
            file = File(directory, "store.json"),
            cipher = KeystoreValueCipher(keyAlias, logger),
            logger = logger,
        )

    @Test
    fun aValueRoundTripsThroughTheRealKeystore() =
        runTest(timeout = 30.seconds) {
            val subject = storage()
            subject.set("refresh", "secret-value")
            assertEquals("secret-value", subject.get("refresh"))
            assertNull(subject.get("never-written"))
        }

    /**
     * The load-bearing assertion, and the reason this test exists on a device at all.
     *
     * Two writes of the same plaintext must produce different bytes, because Keystore generates a fresh IV
     * per operation when randomized encryption is required. A cipher that reused an IV under one AES-GCM key
     * is a real break, not a style problem, and the round-trip test above passes happily with one.
     *
     * Also asserts the plaintext is nowhere in the file, which the JVM test cannot claim: its fake cipher
     * embeds the plaintext deliberately, so only here is the absence meaningful.
     */
    @Test
    fun twoWritesOfTheSameValueProduceDifferentCiphertextAndNoPlaintext() =
        runTest(timeout = 30.seconds) {
            val file = File(directory, "store.json")
            val subject = storage()

            subject.set("refresh", "the-secret")
            val first = file.readText()
            subject.set("refresh", "the-secret")
            val second = file.readText()

            assertNotEquals("the same IV was reused across writes", first, second)
            assertFalse("the plaintext reached the file", first.contains("the-secret"))
            assertFalse("the plaintext reached the file", second.contains("the-secret"))
            assertEquals("the-secret", subject.get("refresh"))
        }

    /**
     * The key lives in the Keystore rather than in the instance, so a new instance over the same directory
     * decrypts what an earlier one wrote. Without this, storage would be memory with extra steps.
     */
    @Test
    fun aNewInstanceDecryptsWhatAnEarlierOneWrote() =
        runTest(timeout = 30.seconds) {
            storage().set("refresh", "secret-value")
            assertEquals("secret-value", storage().get("refresh"))
        }

    /**
     * The key is created on first use, under the alias given, and is a 256-bit AES key.
     *
     * Asserted through the Keystore rather than through our own field, so it reflects what the platform
     * actually holds. Reading the key size back is not possible for a hardware-backed key, so this checks
     * what can be checked: the entry exists and is a secret key under the expected algorithm.
     */
    @Test
    fun theKeyIsCreatedInTheKeystoreOnFirstUse() =
        runTest(timeout = 30.seconds) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertFalse("the alias existed before first use", store.containsAlias(keyAlias))

            storage().set("refresh", "secret-value")

            val after = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertTrue("no key was created", after.containsAlias(keyAlias))
            assertEquals("AES", after.getKey(keyAlias, null)?.algorithm)
        }

    /**
     * A lost key must report as invalidation, not as a generic crypto failure.
     *
     * A genuine `KeyPermanentlyInvalidatedException` needs a biometric or credential change that no automated
     * test can trigger, so this takes the one route a test can: delete the alias under a stored value. The
     * caller-visible contract is identical either way, and asserting the **subtype** rather than merely
     * "some storage exception" is what makes it a test.
     *
     * The looser version of this assertion hid a real defect. Reading used to mint a key when the alias was
     * missing, so the tag check failed and the caller was told `CryptoUnavailable`, meaning transient, when
     * the value was gone for good. `is SecureStorageException` passed happily throughout.
     *
     * Afterwards the store must be usable again rather than permanently throwing on that key, since the
     * unreadable bytes are discarded once reported.
     */
    @Test
    fun aLostKeyReportsInvalidationAndLeavesTheStoreUsable() =
        runTest(timeout = 30.seconds) {
            val subject = storage()
            subject.set("refresh", "secret-value")

            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)

            val thrown = runCatching { subject.get("refresh") }.exceptionOrNull()
            assertTrue(
                "expected KeyInvalidated so the caller re-authenticates, got $thrown",
                thrown is SecureStorageException.KeyInvalidated,
            )
            // No cause means the absent alias was detected before a decryption that could only fail. With a
            // cause, the read minted a key and discovered the problem via the tag check instead, which is the
            // defect this pins: two mechanisms both end at KeyInvalidated, and only this tells them apart.
            assertNull("the missing alias should be detected before decrypting", thrown?.cause)

            assertNull("the unreadable value should have been discarded", subject.get("refresh"))
            subject.set("refresh", "fresh-value")
            assertEquals("the store should be usable again", "fresh-value", subject.get("refresh"))
        }
}
