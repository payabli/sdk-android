package com.payabli.sdk.core.devicekey.platform

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.devicekey.impl.DeviceKeyHandle
import com.payabli.sdk.core.devicekey.impl.EcPointEncoding
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.core.logging.RecordingLogSink
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.logging.impl.LogSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private const val PROVIDER = "AndroidKeyStore"
private val TEST_TIMEOUT = 30.seconds

/**
 * The real Android Keystore, which is the only part of the device key a device is required for.
 *
 * The point encoding and the signature format are covered on the JVM and nothing here repeats them. What is
 * here cannot be shown off-device: `AndroidKeyStore` exists nowhere else, and the authorizations a key was
 * created with can only be read back from a key the platform actually generated.
 *
 * There is one alias and the class takes no parameter for it, so this runs against the entry the app itself
 * uses. It is deleted before and after every test: a leftover key from a previous run would otherwise make a
 * failing implementation look like a passing one.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreDeviceKeyInstrumentedTest {
    private val logger = DefaultSdkLogger(LogCategory.CORE, RecordingLogSink())
    private val keyId = DeviceKeyHandle.ALIAS

    @Before
    fun setUp() {
        runCatching { keyStore().deleteEntry(keyId) }
    }

    @After
    fun tearDown() {
        runCatching { keyStore().deleteEntry(keyId) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(
        beforeKeyGeneration: () -> Unit = {},
        logger: SdkLogger = this.logger,
    ) = KeystoreDeviceKey(logger, beforeKeyGeneration)

    private fun provisioned() = key().apply { ensureKey(mayCreate = true) }

    private fun keyInfo(): KeyInfo {
        val private = keyStore().getKey(keyId, null) as PrivateKey
        // KeyFactory, not SecretKeyFactory: the symmetric factory the storage tests use cannot describe an
        // asymmetric key and throws here.
        return KeyFactory.getInstance(private.algorithm, PROVIDER).getKeySpec(private, KeyInfo::class.java)
    }

    @Test
    fun theKeyIsCreatedWithTheAuthorizationsWeAskedFor() =
        runTest(timeout = TEST_TIMEOUT) {
            provisioned()

            val info = keyInfo()
            assertEquals(256, info.keySize)
            assertTrue("the key must be usable for signing", info.purposes and KeyProperties.PURPOSE_SIGN != 0)
            assertTrue("SHA-256 must be an authorized digest", info.digests.contains(KeyProperties.DIGEST_SHA256))
            // The constraint the ticket names. Binding the key to user presence would make it unusable during
            // the background work it exists for, and it would then fail only on a locked device.
            assertFalse("the device key must not require user authentication", info.isUserAuthenticationRequired)
        }

    @Test
    fun thePublicPointIsTheKeystoreEntrysOwn() =
        runTest(timeout = TEST_TIMEOUT) {
            val subject = provisioned()

            val point = subject.publicKeyPoint()

            assertEquals(65, point.size)
            assertEquals(0x04.toByte(), point[0])
            // Derived independently from the entry's certificate, so this compares the class's answer against
            // the platform's rather than against itself.
            val fromCertificate = keyStore().getCertificate(keyId).publicKey as ECPublicKey
            assertArrayEquals(EcPointEncoding.uncompressed(fromCertificate), point)
        }

    @Test
    fun aSignatureVerifiesAgainstThePublishedPoint() =
        runTest(timeout = TEST_TIMEOUT) {
            val subject = provisioned()
            val payload = "the-signed-bytes".toByteArray()

            val signature = subject.sign(payload)

            // Through the certificate's key rather than the one the class used, which is the same path the
            // service takes: it holds only the point that was sent to it.
            val verified =
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(keyStore().getCertificate(keyId).publicKey)
                    update(payload)
                    verify(signature)
                }
            assertTrue("a signature from the device key did not verify against its own point", verified)
        }

    @Test
    fun theKeyIsRediscoveredByAnotherInstanceOverTheSameAlias() =
        runTest(timeout = TEST_TIMEOUT) {
            val point = provisioned().publicKeyPoint()

            // A second instance holds no state from the first; the key lives in the Keystore. This is as close
            // as an instrumented run gets to a process restart, and the gap is worth knowing: nothing here
            // proves the key survives the process dying, only that it survives the object doing so.
            assertArrayEquals(point, key().apply { ensureKey(mayCreate = false) }.publicKeyPoint())
        }

    @Test
    fun aDeletedAliasReportsTheKeyGoneFromBothReads() =
        runTest(timeout = TEST_TIMEOUT) {
            val subject = provisioned()
            keyStore().deleteEntry(keyId)

            val signing = runCatching { subject.sign("payload".toByteArray()) }.exceptionOrNull()
            val reading = runCatching { subject.publicKeyPoint() }.exceptionOrNull()

            // Not a fresh key. Minting one here would sign with material the service has never attested, under
            // an identifier it already holds a different point for, and every assertion would fail afterwards.
            assertTrue("expected KeyLost from sign, got $signing", signing is DeviceKeyException.KeyLost)
            assertTrue("expected KeyLost from publicKeyPoint, got $reading", reading is DeviceKeyException.KeyLost)
            assertFalse("a read path created a key", keyStore().containsAlias(keyId))
        }

    @Test
    fun ensureKeyRefusesToProvisionWhenItMayNotCreate() =
        runTest(timeout = TEST_TIMEOUT) {
            val thrown = runCatching { key().ensureKey(mayCreate = false) }.exceptionOrNull()

            // The answer to an attested alias whose key is gone is re-attestation, decided at the call site
            // rather than by a default deep inside the class.
            assertTrue("expected KeyLost, got $thrown", thrown is DeviceKeyException.KeyLost)
            assertFalse(keyStore().containsAlias(keyId))
        }

    @Test
    fun aReplacedKeyChangesThePointAndRaisesNothing() =
        runTest(timeout = TEST_TIMEOUT) {
            val before = provisioned().publicKeyPoint()

            keyStore().deleteEntry(keyId)
            val after = key().apply { ensureKey(mayCreate = true) }.publicKeyPoint()

            // Stated as it is rather than as the acceptance first wanted it. A signing key has no equivalent
            // of the storage cipher's tag check: signing under a replaced key succeeds and returns a perfectly
            // valid signature over a key the service never saw. Detecting that locally would need the attested
            // point recorded somewhere, and nothing records it, so the service is what rejects it.
            assertNotEquals(before.toList(), after.toList())
            assertTrue("the replacement must still be usable", key().sign("payload".toByteArray()).isNotEmpty())
        }

    @Test
    fun theIdentityIsDerivedFromTheKeyRatherThanFromTheAlias() =
        runTest(timeout = TEST_TIMEOUT) {
            val before = provisioned().identity()

            keyStore().deleteEntry(keyId)
            val after = key().apply { ensureKey(mayCreate = true) }.identity()

            // The alias is identical across the replacement, so an identity taken from it would be identical
            // too and the service would hold one identifier for two different keys.
            assertNotEquals(before, after)
            // Derived, not remembered: a second instance holding no state reports the same value.
            assertEquals(after, key().identity())
        }

    @Test
    fun theIdentityOfAnAbsentKeyReportsItGone() =
        runTest(timeout = TEST_TIMEOUT) {
            val thrown = runCatching { key().identity() }.exceptionOrNull()

            // Not an empty string and not a thumbprint of nothing, either of which the service would accept and
            // then be unable to match against any key.
            assertTrue("expected KeyLost, got $thrown", thrown is DeviceKeyException.KeyLost)
        }

    @Test
    fun deleteRemovesTheKeyAndSucceedsWhenThereIsNothingToRemove() =
        runTest(timeout = TEST_TIMEOUT) {
            val subject = provisioned()

            subject.delete()
            assertFalse("the key survived a delete", keyStore().containsAlias(keyId))

            // A caller that could not tell whether its first attempt completed repeats it. Throwing here would
            // turn a successful cleanup into a failure the second time it is asked for.
            subject.delete()
            assertFalse(keyStore().containsAlias(keyId))
        }

    /**
     * Two callers over one alias must generate exactly one key.
     *
     * **Generations are counted, not inferred from the survivor.** Whatever the second caller does, the alias
     * ends up holding one key with a 65-byte point, so inspecting the alias afterwards passes with the monitor
     * removed. `createKey` logs once per generation, which is the only place the second one is visible.
     *
     * The counter is its own sink because two racing generations would write it from two threads, and the
     * shared recording sink appends to a plain list: a lost update there reports one generation and turns a
     * broken monitor green. The storage cipher's equivalent test counts the same way.
     *
     * The rendezvous is what makes them collide. It sits after the unsynchronized presence check and before the
     * guarded creation, so both callers are provably inside the window; without it the window is a few
     * microseconds and two coroutines launched together almost never meet in it.
     */
    @Test
    fun twoCallersOverOneAliasGenerateExactlyOneKey() =
        runTest(timeout = TEST_TIMEOUT) {
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
                            if ("device key created" in message) generations.incrementAndGet()
                        }
                    },
                )
            val barrier = CyclicBarrier(2)
            val rendezvous = {
                barrier.await(TEST_TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)
                Unit
            }

            listOf(
                async(Dispatchers.IO) { key(rendezvous, countingLogger).ensureKey(mayCreate = true) },
                async(Dispatchers.IO) { key(rendezvous, countingLogger).ensureKey(mayCreate = true) },
            ).awaitAll()

            assertEquals(
                "the key was generated more than once for one alias, so the service holds a point that is gone",
                1,
                generations.get(),
            )
            // The consequence still holds, which is why generating once matters.
            assertTrue(keyStore().containsAlias(keyId))
            assertEquals(65, key().publicKeyPoint().size)
        }

    @Test
    fun theStrongBoxFallbackProducesAUsableKeyWhereStrongBoxIsAbsent() =
        runTest(timeout = TEST_TIMEOUT) {
            val hasStrongBox =
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext
                    .packageManager
                    .hasSystemFeature("android.hardware.strongbox_keystore")

            val point = provisioned().publicKeyPoint()

            // On an emulator the fallback is the branch that runs, which is what proves
            // StrongBoxUnavailableException is not swallowed: swallowed, generation fails outright here.
            assertEquals(65, point.size)
            if (!hasStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertNotEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, keyInfo().securityLevel)
            }
        }
}
