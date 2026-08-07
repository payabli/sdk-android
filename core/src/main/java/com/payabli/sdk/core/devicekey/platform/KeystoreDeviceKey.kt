package com.payabli.sdk.core.devicekey.platform

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import com.payabli.sdk.core.devicekey.DeviceKey
import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.devicekey.DeviceSignature
import com.payabli.sdk.core.devicekey.impl.DeviceKeyHandle
import com.payabli.sdk.core.devicekey.impl.EcPointEncoding
import com.payabli.sdk.core.devicekey.impl.EcdsaSigner
import com.payabli.sdk.core.devicekey.impl.JwkThumbprint
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * An EC P-256 keypair in the Android Keystore, signing on request and never yielding the private half.
 *
 * In `platform` because the Keystore has no JVM implementation, so no unit test can reach a line of this file
 * and the instrumented suite is what covers it. The two things that decide correctness, the point encoding and
 * the signature format, are not here: they are plain platform code and are unit-tested.
 *
 * **Reading never creates.** An absent alias reports the key gone; only [ensureKey] provisions. A key minted
 * on a read path would sign with material the service has never seen, under an identifier it already holds a
 * different point for, and every assertion would then fail verification with nothing pointing at the cause.
 * The storage cipher makes the same rule for a different reason: there, a key minted on read turns a lost key
 * into a tag failure. Here there is no tag to fail, which makes the rule matter more rather than less.
 *
 * Neither half of the key is cached. A handle held across a delete keeps working against a key the store no
 * longer has, and it is a live reference to material whose lifetime this class does not own.
 */
internal class KeystoreDeviceKey(
    private val logger: SdkLogger,
    /**
     * Runs after the unsynchronized presence check and before the guarded generation. **A test seam, no-op in
     * production**, and it exists because the race it opens cannot be reached from outside this class.
     *
     * Two instances both finding no key and both generating would leave the second key in place and the first
     * attested key gone. From outside [ensureKey] the check and the creation are atomic, so nothing can
     * interleave them, and a test that races two callers observes a consequence rather than the invariant.
     * With a rendezvous here the test asserts the invariant instead: one generation, counted from the log line
     * [createKey] emits.
     */
    private val beforeKeyGeneration: () -> Unit = {},
    /**
     * Runs inside [sign], between producing the signature and deriving the identity that labels it. **A test
     * seam, no-op in production.**
     *
     * The window it opens is closed by the monitor both [sign] and [delete] take, so nothing outside this
     * class can observe it: a replacement attempted here blocks until the signature and its identity have
     * been read from one key. Without a seam a test could only assert that the pair happens to agree, which
     * it does whether or not the monitor is there.
     */
    private val betweenSignAndIdentity: () -> Unit = {},
) : DeviceKey {
    /**
     * There is no alias parameter, and that is the point.
     *
     * A parameter here could carry a per-key name, which is the shape this class exists to make unwritable:
     * nothing would fail, and the key it replaced would be left in the store with nothing able to name it.
     * The alias is the same on every install and is read from one place.
     */
    private val alias: String get() = DeviceKeyHandle.ALIAS

    override fun identity(): String = JwkThumbprint.of(publicKeyPoint())

    override fun delete() {
        // Not `discarding()`: that reports the key gone because a signature proved it unusable. This is a
        // caller acting on what the service said, and it succeeds rather than throwing when the key is
        // already absent, because a repeat of an attempt that may have completed must not fail.
        //
        // Under the same monitor as `sign` and `ensureKey`, so a replacement cannot land between a signature
        // and the identity that labels it. Removing the key is one half of a replacement; the other half is
        // `ensureKey`, which takes the monitor too, so the pair is serialised against signing as a whole.
        synchronized(MONITOR) {
            try {
                keyStore().deleteEntry(alias)
            } catch (e: GeneralSecurityException) {
                throw DeviceKeyException.CryptoUnavailable(e)
            } catch (e: ProviderException) {
                throw asProviderFailure(e)
            }
        }
    }

    override fun publicKeyPoint(): ByteArray {
        val key = existingPublicKey() ?: throw DeviceKeyException.KeyLost()
        return try {
            EcPointEncoding.uncompressed(key)
        } catch (e: IllegalArgumentException) {
            // Not a P-256 point, so not a key this minted. Discarding it is the same judgement the invalidated
            // cases make: leaving it would fail every later call identically with nothing able to clear it.
            throw discarding(e)
        }
    }

    /**
     * Both values from one key, under the monitor that replacement also takes.
     *
     * The private half and the public half are two reads of the key store. Between them a replacement would
     * otherwise leave the signature made by one key and the identity naming another, which the service
     * rejects because it verifies against the public key it holds for the identity it was sent.
     */
    override fun sign(payload: ByteArray): DeviceSignature =
        synchronized(MONITOR) {
            val signature =
                try {
                    EcdsaSigner.sign(privateKeyForSigning(), payload)
                } catch (e: GeneralSecurityException) {
                    throw asFailure(e)
                } catch (e: ProviderException) {
                    throw asProviderFailure(e)
                }
            betweenSignAndIdentity()
            DeviceSignature(signature, identity())
        }

    /**
     * Provisions the alias, and is the only place a key is ever created.
     *
     * Double-checked under the shared monitor, so two callers arriving together generate once rather than
     * racing to replace each other's key.
     *
     * With [mayCreate] false it proves a key is present, which is what a caller resolving a key the service has
     * already accepted wants: the answer to a missing key there is enrolling again, not a fresh key under the
     * same alias.
     */
    fun ensureKey(mayCreate: Boolean) {
        if (existingPrivateKey() != null) return
        beforeKeyGeneration()
        synchronized(MONITOR) {
            if (existingPrivateKey() != null) return
            if (!mayCreate) throw DeviceKeyException.KeyLost()
            createKey()
        }
    }

    /**
     * A Keystore backend failure, which arrives outside the checked hierarchy everything else here maps.
     *
     * `ProviderException` extends `RuntimeException`, so without this a keystore daemon that is unreachable
     * escapes the [DeviceKeyException] surface as a raw runtime failure and the caller loses the one
     * distinction the surface exists to make. Always the platform case: a broken provider says nothing about
     * whether this key survives.
     *
     * Deliberately **not** mapped inside [generate]. `StrongBoxUnavailableException` is itself a
     * `ProviderException`, so a catch there swallows it, [strongBoxKey] never sees the signal it falls back
     * on, and key creation fails outright on every device without a secure element.
     */
    private fun asProviderFailure(cause: ProviderException): DeviceKeyException =
        DeviceKeyException.CryptoUnavailable(cause)

    /**
     * Maps a platform failure by whether the key survives it.
     *
     * `KeyPermanentlyInvalidatedException` is matched by name rather than type: it extends
     * `InvalidKeyException`, so a reordered catch block would silently demote it to a signing failure and the
     * caller would retry a device that can never recover.
     */
    private fun asFailure(cause: GeneralSecurityException): DeviceKeyException =
        when {
            cause is UnrecoverableKeyException || cause::class.java.name == KEY_INVALIDATED -> discarding(cause)
            else -> DeviceKeyException.SigningFailed(cause)
        }

    /**
     * Reports the key gone, having removed the alias that named it.
     *
     * A failed removal is reported as the platform being unavailable instead: the cleanup [KeyLost] documents
     * did not happen, so claiming it would be false, and every later call would meet the same unusable alias.
     */
    private fun discarding(cause: Throwable): DeviceKeyException {
        logger.warn(LogField.safe("event", "device_key_discarded")) { "device key unusable, discarding alias" }
        return if (runCatching { keyStore().deleteEntry(alias) }.isSuccess) {
            DeviceKeyException.KeyLost(cause)
        } else {
            DeviceKeyException.CryptoUnavailable(cause)
        }
    }

    private fun keyStore(): KeyStore =
        try {
            KeyStore.getInstance(PROVIDER).apply { load(null) }
        } catch (e: GeneralSecurityException) {
            throw DeviceKeyException.CryptoUnavailable(e)
        } catch (e: java.io.IOException) {
            throw DeviceKeyException.CryptoUnavailable(e)
        } catch (e: ProviderException) {
            throw asProviderFailure(e)
        }

    private fun privateKeyForSigning(): PrivateKey = existingPrivateKey() ?: throw DeviceKeyException.KeyLost()

    private fun existingPrivateKey(): PrivateKey? =
        try {
            keyStore().getKey(alias, null) as? PrivateKey
        } catch (e: UnrecoverableKeyException) {
            throw asFailure(e)
        } catch (e: GeneralSecurityException) {
            throw DeviceKeyException.CryptoUnavailable(e)
        } catch (e: ProviderException) {
            throw asProviderFailure(e)
        }

    /**
     * The public half, read from the entry's self-signed certificate.
     *
     * A Keystore keypair entry carries one, and it is the only way to reach the public key: `getKey` answers
     * with the private half alone.
     */
    private fun existingPublicKey(): ECPublicKey? =
        try {
            keyStore().getCertificate(alias)?.publicKey as? ECPublicKey
        } catch (e: GeneralSecurityException) {
            throw DeviceKeyException.CryptoUnavailable(e)
        } catch (e: ProviderException) {
            throw asProviderFailure(e)
        }

    /**
     * StrongBox where the platform has it, otherwise whatever the platform gives.
     *
     * The level obtained is logged rather than enforced. Requiring a secure element would fail on emulators and
     * on devices without one, so production accepts what it gets and the manual device tier is where hardware
     * backing is asserted.
     */
    private fun createKey(): PrivateKey {
        // StrongBox is API 28 while this module's floor is 23: unguarded, this is a NoSuchMethodError on
        // 23 to 27, which no test device would surface because they are all newer.
        val key =
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    strongBoxKey() ?: generate(baseSpec().build())
                } else {
                    generate(baseSpec().build())
                }
            } catch (e: ProviderException) {
                // Here rather than in generate(): StrongBoxUnavailableException is a ProviderException, so
                // catching it there would swallow the signal strongBoxKey() falls back on.
                throw asProviderFailure(e)
            }
        logger.debug(LogField.safe("securityLevel", securityLevelOf(key))) { "device key created" }
        return key
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun strongBoxKey(): PrivateKey? =
        try {
            generate(baseSpec().setIsStrongBoxBacked(true).build())
        } catch (_: StrongBoxUnavailableException) {
            null
        }

    /**
     * No `setUserAuthenticationRequired`: this key signs during background work with nobody present, and
     * binding it to user presence would make that impossible. The storage key omits it for the same reason.
     *
     * `setDigests(DIGEST_SHA256)` is not decoration. A key generated without it is refused at `initSign` with
     * an incompatible-digest failure, which surfaces as a signing error on a key that was never usable.
     */
    private fun baseSpec(): KeyGenParameterSpec.Builder =
        KeyGenParameterSpec
            .Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)

    /** Best effort, for the log line only. `getSecurityLevel` is API 31; below that the answer is coarser. */
    private fun securityLevelOf(key: PrivateKey): String =
        runCatching {
            // KeyFactory, not SecretKeyFactory: this is an asymmetric key, and the symmetric factory the
            // storage cipher uses cannot describe one.
            val info = KeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "tee"
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                    else -> "unknown"
                }
            } else {
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) "hardware" else "software"
            }
        }.getOrDefault("unknown")

    private fun generate(spec: KeyGenParameterSpec): PrivateKey =
        try {
            KeyPairGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
                .apply { initialize(spec) }
                .generateKeyPair()
                .private
        } catch (e: GeneralSecurityException) {
            throw DeviceKeyException.CryptoUnavailable(e)
        }

    private companion object {
        /**
         * One monitor, shared by every instance, held by [sign], [delete] and [ensureKey].
         *
         * There is one alias, so there is one thing to serialise on. The storage cipher keys its monitor by
         * alias because it has one per store; here a map would be a map with a single entry.
         *
         * It covers two invariants rather than one: that two callers finding no key generate once, and that a
         * signature and the identity labelling it come from the same key. The second is why [sign] takes it,
         * since signing alone needs no mutual exclusion.
         */
        private val MONITOR = Any()

        const val PROVIDER = "AndroidKeyStore"
        const val CURVE = "secp256r1"

        const val KEY_INVALIDATED = "android.security.keystore.KeyPermanentlyInvalidatedException"
    }
}
