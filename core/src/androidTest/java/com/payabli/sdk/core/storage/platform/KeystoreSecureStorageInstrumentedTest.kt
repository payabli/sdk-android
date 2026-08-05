package com.payabli.sdk.core.storage.platform

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.logging.impl.LogSink
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.core.storage.impl.FileSecureStorage
import com.payabli.sdk.core.storage.impl.ValueCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
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
    private val logger = DefaultSdkLogger(LogCategory.CORE, sink)
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
     * which fails because the bytes were sealed under the previous key. What the mapping is for is telling the
     * caller the value is gone for good rather than transient: without it this reports `CryptoUnavailable`, and a
     * caller retries forever instead of re-obtaining that entry.
     *
     * **Re-obtaining, not re-authenticating.** Re-authentication is `KeyInvalidated`'s action, and this path
     * deliberately does not take it: a replaced key is indistinguishable from a corrupted blob on the read side, so
     * only the entry asked for is discarded.
     */
    @Test
    fun aReplacedKeyReportsTheValueAsUnreadable() =
        runTest(timeout = 30.seconds) {
            val subject = storage()
            subject.set("refresh", "secret-value".toByteArray())

            // Delete and recreate under the same alias: from the read side the key simply changed.
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(keyAlias)
            // ensureKey, because encrypt no longer creates: provisioning is a separate operation now.
            KeystoreValueCipher(keyAlias, logger).ensureKey(mayCreate = true)

            val thrown = runCatching { subject.get("refresh") }.exceptionOrNull()
            // ValueUnreadable rather than KeyInvalidated: the alias exists, so the read reaches the tag
            // check, and a replaced key is indistinguishable from a corrupted blob there. Discarding only
            // the entry asked for is the conservative choice.
            assertTrue(
                "expected ValueUnreadable for a replaced key, got $thrown",
                thrown is SecureStorageException.ValueUnreadable,
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
            subject.set("damaged", "first-value".toByteArray())
            subject.set("intact", "second-value".toByteArray())

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
                "a corrupt blob is a bad value, not a lost key, got $thrown",
                thrown is SecureStorageException.ValueUnreadable,
            )

            assertArrayEquals(
                "the other entry was destroyed by a failure that had nothing to do with it",
                "second-value".toByteArray(),
                subject.get("intact"),
            )
        }

    /**
     * A truncated blob is corruption, not a bad value and not a lost key.
     *
     * Twenty bytes is longer than the 12-byte IV but shorter than IV plus the 16-byte tag. Without a guard
     * on the full minimum it is valid base64 that reaches `doFinal`, throws a tag failure, and gets reported
     * as an unreadable value, which contradicts the classification the code itself claims.
     */
    @Test
    fun aTruncatedBlobReportsStorageCorruption() =
        runTest(timeout = 30.seconds) {
            val file = File(directory, "store.json")
            val subject = storage()
            subject.set("refresh", "secret-value".toByteArray())

            val serializer = MapSerializer(String.serializer(), String.serializer())
            val map = Json.decodeFromString(serializer, file.readText())
            val truncated = Base64.encodeToString(ByteArray(20), Base64.NO_WRAP)
            file.writeText(Json.encodeToString(serializer, map + ("refresh" to truncated)))

            val thrown = runCatching { subject.get("refresh") }.exceptionOrNull()
            assertTrue(
                "a truncated blob is corruption, got $thrown",
                thrown is SecureStorageException.StorageUnavailable,
            )
        }

    private fun storage(
        fileName: String = "store.json",
        cipher: ValueCipher = KeystoreValueCipher(keyAlias, logger),
    ) = FileSecureStorage(
        file = File(directory, fileName),
        cipher = cipher,
        logger = logger,
    )

    /**
     * `encrypt` must never create a key, which is the cipher-level half of the provisioning split.
     *
     * Storage decides whether creation is allowed and says so once, through `ensureKey`. If `encrypt` can also
     * create, that decision is bypassed whenever the alias disappears between the two calls: the value gets
     * sealed under a fresh key, every earlier blob becomes unreadable, and nothing reports it.
     *
     * Asserted directly against the cipher rather than through storage, because storage's fake cannot show what
     * the real Keystore does, and because a `KeyInvalidated` from `encrypt` is only correct if no key was left
     * behind. Both halves are checked.
     */
    @Test
    fun encryptDoesNotCreateAKeyWhenTheAliasIsGone() =
        runTest(timeout = 30.seconds) {
            val cipher = KeystoreValueCipher(keyAlias, logger)
            cipher.ensureKey(mayCreate = true)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)

            val thrown = runCatching { cipher.encrypt("refresh", "secret-value".toByteArray()) }.exceptionOrNull()

            assertTrue(
                "expected KeyInvalidated rather than a silently minted replacement, got $thrown",
                thrown is SecureStorageException.KeyInvalidated,
            )
            assertFalse(
                "encrypt created a replacement key, which strands every blob sealed by the previous one",
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(keyAlias),
            )
        }

    /**
     * Two stores built by the factory must not share a key alias, including when only the directory differs.
     *
     * The isolation the continuity argument rests on. A cipher can cheaply prove a key is *present*, not that it
     * is the key that sealed the blobs, so the guarantee has to come from nobody else owning the alias. Deriving
     * from the file name alone was not enough: `create(dirA)` and `create(dirB)` on the default name shared one
     * alias while holding separate locks, which is the same collision one step smaller.
     *
     * The equal-name-different-directory pair is the case that mattered and is asserted first. It also proves the
     * Keystore accepts an alias of this length, which is otherwise an assumption about the derived name.
     */
    @Test
    fun twoStoresFromTheFactoryDoNotShareAKeyAlias() =
        runTest(timeout = 30.seconds) {
            val dirA = File(directory, "a").apply { mkdirs() }
            val dirB = File(directory, "b").apply { mkdirs() }
            val sameNameA = File(dirA, "store.json")
            val sameNameB = File(dirB, "store.json")
            val differentName = File(dirA, "other.json")

            assertNotEquals(
                "equal file names in different directories derived one alias",
                SecureStorageFactory.aliasFor(sameNameA),
                SecureStorageFactory.aliasFor(sameNameB),
            )
            assertNotEquals(
                SecureStorageFactory.aliasFor(sameNameA),
                SecureStorageFactory.aliasFor(differentName),
            )

            val first = SecureStorageFactory.create(dirA, fileName = "store.json", logger = logger)
            val second = SecureStorageFactory.create(dirB, fileName = "store.json", logger = logger)
            try {
                first.set("refresh", "first-value".toByteArray())
                second.set("refresh", "second-value".toByteArray())

                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                assertTrue(
                    "the first store's key is missing, so the derived alias was not usable",
                    store.containsAlias(SecureStorageFactory.aliasFor(sameNameA)),
                )
                assertTrue(
                    "the second store's key is missing",
                    store.containsAlias(SecureStorageFactory.aliasFor(sameNameB)),
                )
                // Neither store disturbed the other, which a shared alias would have made a coin flip.
                assertArrayEquals("first-value".toByteArray(), first.get("refresh"))
                assertArrayEquals("second-value".toByteArray(), second.get("refresh"))
            } finally {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                listOf(sameNameA, sameNameB, differentName).forEach {
                    runCatching { store.deleteEntry(SecureStorageFactory.aliasFor(it)) }
                }
            }
        }

    /**
     * Two ciphers over one alias must generate exactly one key.
     *
     * **The invariant, not its consequence.** An earlier version raced two writes and asserted both values were
     * still readable, which caught the missing monitor in 3 of 3 whole-suite runs but only about half the time in
     * isolation, and a start barrier did not help. Data is lost only when one store finishes encrypting *between*
     * the two generations, so tighter overlap puts both generations before either encrypt, both blobs end up under
     * the final key, and nothing is lost. The detection window was bounded on both sides.
     *
     * Counting generations removes the timing. `beforeKeyGeneration` is a rendezvous placed after the
     * unsynchronized presence check and before the guarded creation, so both callers are provably inside the
     * window, and `createKey` already logs once per generation. With the monitor, one thread creates and the other
     * finds the key on the second check. Without it, both create.
     *
     * The factory cannot produce two stores over one alias; only direct construction can, which is internal code,
     * so the monitor is defence for that shape and this proves the defence works.
     */
    @Test
    fun twoCiphersOverOneAliasGenerateExactlyOneKey() =
        runTest(timeout = 30.seconds) {
            val generations = AtomicInteger(0)
            val countingLogger =
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
                            if ("storage key created" in message) generations.incrementAndGet()
                        }
                    },
                )
            val rendezvous = CyclicBarrier(2)
            val hook = {
                rendezvous.await(10, TimeUnit.SECONDS)
                Unit
            }
            val first = storage("first.json", KeystoreValueCipher(keyAlias, countingLogger, hook))
            val second = storage("second.json", KeystoreValueCipher(keyAlias, countingLogger, hook))

            listOf(
                async(Dispatchers.IO) { first.set("refresh", "first-value".toByteArray()) },
                async(Dispatchers.IO) { second.set("refresh", "second-value".toByteArray()) },
            ).awaitAll()

            assertEquals(
                "the key was generated more than once for one alias, so one store's ciphertext is stranded",
                1,
                generations.get(),
            )
            // The consequence still holds, which is why generating once matters.
            assertArrayEquals("first-value".toByteArray(), first.get("refresh"))
            assertArrayEquals("second-value".toByteArray(), second.get("refresh"))
        }

    @Test
    fun aValueRoundTripsThroughTheRealKeystore() =
        runTest(timeout = 30.seconds) {
            val subject = storage()
            subject.set("refresh", "secret-value".toByteArray())
            assertArrayEquals("secret-value".toByteArray(), subject.get("refresh"))
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

            subject.set("refresh", "the-secret".toByteArray())
            val first = file.readText()
            subject.set("refresh", "the-secret".toByteArray())
            val second = file.readText()

            assertNotEquals("the same IV was reused across writes", first, second)
            assertFalse("the plaintext reached the file", first.contains("the-secret"))
            assertFalse("the plaintext reached the file", second.contains("the-secret"))
            assertArrayEquals("the-secret".toByteArray(), subject.get("refresh"))
        }

    /**
     * The key lives in the Keystore rather than in the instance, so a new instance over the same directory
     * decrypts what an earlier one wrote. Without this, storage would be memory with extra steps.
     */
    @Test
    fun aNewInstanceDecryptsWhatAnEarlierOneWrote() =
        runTest(timeout = 30.seconds) {
            storage().set("refresh", "secret-value".toByteArray())
            assertArrayEquals("secret-value".toByteArray(), storage().get("refresh"))
        }

    /**
     * The key is created on first use, under the alias given.
     *
     * Asserted through the Keystore rather than through our own field, so it reflects what the platform
     * actually holds. What the key was created *with* is
     * [theKeyIsCreatedWithTheAuthorizationsWeAskedFor]; this is only about the entry appearing.
     */
    @Test
    fun theKeyIsCreatedInTheKeystoreOnFirstUse() =
        runTest(timeout = 30.seconds) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertFalse("the alias existed before first use", store.containsAlias(keyAlias))

            storage().set("refresh", "secret-value".toByteArray())

            val after = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertTrue("no key was created", after.containsAlias(keyAlias))
            assertEquals("AES", after.getKey(keyAlias, null)?.algorithm)
        }

    /**
     * The key's authorizations are what the spec asked for, read back from the platform.
     *
     * Here rather than in the manual tier on purpose. These *values* are readable wherever Keystore exists, so
     * the nightly catches a regression in them; whether a secure element **enforces** them is the separate
     * question the manual tier asks. An earlier comment claimed the size could not be read back for a
     * hardware-backed key. It can, on an emulator and on both test phones, so the claim is gone rather than
     * qualified.
     *
     * `isUserAuthenticationRequired` is the load-bearing one. The spec omits `setUserAuthenticationRequired`
     * deliberately, because the first consumer is a secret read during background token refresh with nobody
     * present, and because it is why no credential-change procedure for `KeyPermanentlyInvalidatedException`
     * exists. Bind the key to user presence and background refresh breaks at runtime with nothing here to
     * notice, which is exactly the kind of silent regression this asserts against.
     */
    @Test
    fun theKeyIsCreatedWithTheAuthorizationsWeAskedFor() =
        runTest(timeout = 30.seconds) {
            storage().set("refresh", "secret-value".toByteArray())
            val info = keyInfo()

            assertEquals("the key is not AES-256", 256, info.keySize)
            assertArrayEquals(
                "block modes other than GCM are authorized",
                arrayOf(KeyProperties.BLOCK_MODE_GCM),
                info.blockModes,
            )
            assertArrayEquals(
                "paddings other than none are authorized",
                arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE),
                info.encryptionPaddings,
            )
            assertEquals(
                "purposes beyond encrypt and decrypt are authorized",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                info.purposes,
            )
            assertEquals("the key was not generated inside the keystore", KeyProperties.ORIGIN_GENERATED, info.origin)
            assertFalse(
                "the key is bound to user authentication, which would break background token refresh",
                info.isUserAuthenticationRequired,
            )
        }

    /**
     * Where StrongBox is absent, the fallback must still produce a usable key.
     *
     * The question only an emulator can answer, because it advertises no `strongbox_keystore` and both test
     * phones do. `strongBoxKey()` must return null through `StrongBoxUnavailableException` and creation must
     * continue with a plain spec. If that signal is ever swallowed, key creation fails outright on every device
     * without StrongBox, and this is the only place that would see it.
     *
     * The level is asserted as software rather than as hardware, because that is what an emulator has, and it
     * is what proves the fallback ran rather than a StrongBox key having been made.
     *
     * **The one `Assume` in either instrumented file, and it is sound here.** The no-skip rule exists because
     * the nightly reporter counts skips, and the nightly runs on an emulator, where this never skips. It opts
     * out only when somebody points this suite at a phone, where StrongBox exists and there is genuinely no
     * fallback to observe. Asserting instead would fail a correct implementation on correct hardware.
     */
    @Test
    fun theStrongBoxFallbackProducesAUsableKeyWhereStrongBoxIsAbsent() =
        runTest(timeout = 30.seconds) {
            Assume.assumeFalse(
                "StrongBox is present, so there is no fallback to observe; this runs on an emulator",
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext
                    .packageManager
                    .hasSystemFeature("android.hardware.strongbox_keystore"),
            )

            val subject = storage()
            subject.set("refresh", "secret-value".toByteArray())

            assertArrayEquals(
                "the fallback key could not decrypt what it encrypted",
                "secret-value".toByteArray(),
                subject.get("refresh"),
            )
            // Not-StrongBox, rather than software. A device without a secure element can still have a trusted
            // execution environment, and the fallback lands there: asserting software failed on a TEE-only
            // handset, which is a correct fallback reported as a defect. What this test is for is that the
            // fallback ran at all, and a StrongBox key is the only result that proves it did not.
            //
            // Below 31 the platform cannot report a level, only whether the key is in secure hardware, and
            // that question cannot separate a TEE fallback from a StrongBox key. There the round trip above is
            // the whole assertion.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertNotEquals(
                    "no StrongBox is present, so a StrongBox key means the fallback did not run",
                    KeyProperties.SECURITY_LEVEL_STRONGBOX,
                    keyInfo().securityLevel,
                )
            }
        }

    private fun keyInfo(): KeyInfo {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(keyAlias, null) as SecretKey
        return SecretKeyFactory
            .getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
    }

    /**
     * A lost key must report as invalidation, not as a generic crypto failure.
     *
     * `KeyPermanentlyInvalidatedException` is handled defensively rather than reachably: this key is not
     * bound to user authentication, so an enrollment or credential change does not invalidate it. Deleting
     * and replacing the alias are the reachable lost-key outcomes, and both are covered. Asserting the
     * **subtype** rather than merely "some storage exception" is what makes these tests.
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
            subject.set("refresh", "secret-value".toByteArray())

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
            subject.set("refresh", "fresh-value".toByteArray())
            assertArrayEquals("the store should be usable again", "fresh-value".toByteArray(), subject.get("refresh"))
        }

    /**
     * Writing after the alias is deleted, with no read in between, must report the loss rather than mint a key.
     *
     * The ordering is what makes this its own test: a write cannot tell a fresh install from a lost key, and
     * creating one is right for the first and wrong for the second. Left to create, the new blob lands beside
     * ciphertext sealed under the key that is gone, nothing reports the loss, and each old value fails alone
     * on some later read as though it were individually corrupt.
     */
    @Test
    fun deletingTheAliasThenWritingReportsInvalidationRatherThanMintingAKey() =
        runTest(timeout = 30.seconds) {
            val subject = storage()
            subject.set("first", "one".toByteArray())
            subject.set("second", "two".toByteArray())

            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)

            val thrown = runCatching { subject.set("third", "three".toByteArray()) }.exceptionOrNull()
            assertTrue(
                "expected KeyInvalidated on a write into a store whose key is gone, got $thrown",
                thrown is SecureStorageException.KeyInvalidated,
            )

            // Cleared, so the next write starts from nothing rather than mixing a new key with old blobs.
            assertNull("the stranded entry should be gone", subject.get("first"))
            assertNull("the stranded entry should be gone", subject.get("second"))
            subject.set("third", "three".toByteArray())
            assertArrayEquals("the store should be usable again", "three".toByteArray(), subject.get("third"))
        }
}
