package com.payabli.sdk.core.devicekey.platform

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import com.payabli.sdk.core.devicekey.DeviceKey
import com.payabli.sdk.core.devicekey.DeviceKeyException
import com.payabli.sdk.core.devicekey.DevicePublicKey
import com.payabli.sdk.core.devicekey.DeviceSignature
import com.payabli.sdk.core.devicekey.impl.DeviceKeyHandle
import com.payabli.sdk.core.devicekey.impl.EcPointEncoding
import com.payabli.sdk.core.devicekey.impl.EcdsaSigner
import com.payabli.sdk.core.devicekey.impl.JwkThumbprint
import com.payabli.sdk.core.keystore.KeystoreFailures
import com.payabli.sdk.core.keystore.KeystoreVerdict
import com.payabli.sdk.core.keystore.platform.PlatformKeystoreReading
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import java.security.GeneralSecurityException
import java.security.Key
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
 * In `platform` because the Keystore has no JVM implementation. Reading never creates: only [ensureKey]
 * provisions. Neither half is cached. `MONITOR` is process-local, so two processes can race and the loser enrols again.
 *
 * @property beforeKeyGeneration a test seam, no-op in production: a rendezvous opening the generation race, so a test asserts one generation rather than a consequence.
 * @property betweenSignAndIdentity a test seam, no-op in production: opens the window inside [sign] that the monitor both [sign] and [delete] takes closes.
 * @property readPrivateKey a test seam; production reads the key store, and this is how a test reaches the discard-and-create path.
 */
internal class KeystoreDeviceKey(
    private val logger: SdkLogger,
    private val beforeKeyGeneration: () -> Unit = {},
    private val betweenSignAndIdentity: () -> Unit = {},
    private val readPrivateKey: (KeyStore, String) -> Key? = { store, alias -> store.getKey(alias, null) },
) : DeviceKey {
    /**
     * There is no alias parameter, and that is the point.
     *
     * A parameter here could carry a per-key name, which is the shape this class exists to make unwritable:
     * nothing would fail, and the key it replaced would be left in the store with nothing able to name it.
     * The alias is the same on every install and is read from one place.
     */
    private val alias: String get() = DeviceKeyHandle.ALIAS

    /**
     * The point and its identifier from one read, under the monitor replacement also takes: the identifier
     * is derived from the point just read, and [uncompressedPoint] discards rather than reads, so the lock
     * belongs here.
     *
     * @return the point and the thumbprint naming it, from one read.
     */
    override fun publicKey(): DevicePublicKey =
        synchronized(MONITOR) {
            val point = uncompressedPoint()
            DevicePublicKey(point, JwkThumbprint.of(point))
        }

    override fun delete() {
        // Not `discarding()`: that reports the key gone because a signature proved it unusable. This is a
        // caller acting on what the service said, and it succeeds rather than throwing when the key is
        // already absent, because a repeat of an attempt that may have completed must not fail.
        //
        // Under the same monitor as `sign`, so a deletion cannot land between a signature and the identity
        // that labels it.
        //
        // The monitor is released on return, before any new key is created. A `sign` that runs after this
        // and before the next key exists throws `KeyLost`.
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

    override fun provision() {
        ensureKey(mayCreate = true)
    }

    private fun uncompressedPoint(): ByteArray {
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
     * Both values from one key, under the monitor that replacement also takes: between the private half's
     * read and the public half's, a replacement would leave the signature made by one key and the identity
     * naming another. The monitor is process-local, as the class says.
     *
     * @param payload the bytes to sign.
     * @return the signature with the identity of the key that made it.
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
            // Reentrant on the monitor already held, so the pair still comes from one observation.
            DeviceSignature(signature, publicKey().identity)
        }

    /**
     * Provisions the alias, and is the only place a key is ever created. Guarded by the shared monitor, and
     * the presence check is inside it: the check discards an unloadable entry, so an unsynchronized probe
     * could delete a key another caller had just generated. With [mayCreate] false it proves a key is present.
     *
     * @param mayCreate whether a missing key is created; false only proves one is present.
     */
    fun ensureKey(mayCreate: Boolean) {
        beforeKeyGeneration()
        synchronized(MONITOR) {
            val existing =
                try {
                    existingPrivateKey()
                } catch (discarded: DeviceKeyException.KeyLost) {
                    if (!mayCreate) throw discarded
                    null
                }
            if (existing != null) return
            if (!mayCreate) throw DeviceKeyException.KeyLost()
            createKey()
        }
    }

    /**
     * A Keystore backend failure, outside the checked hierarchy everything else here maps: without this,
     * a keystore daemon that is unreachable escapes the [DeviceKeyException] surface as a raw runtime
     * failure. Not mapped inside [generate], which would swallow [strongBoxKey]'s fallback signal.
     *
     * @param cause the backend failure the platform raised.
     * @return a CryptoUnavailable carrying the cause.
     */
    private fun asProviderFailure(cause: ProviderException): DeviceKeyException =
        DeviceKeyException.CryptoUnavailable(cause)

    /**
     * Maps a platform failure by whether the key survives it, and otherwise by Keystore's own verdict.
     * `KeyPermanentlyInvalidatedException` is matched by name rather than type, so a reordered catch
     * block cannot silently demote it to a signing failure the caller would retry.
     *
     * @param cause the platform failure the signing path raised.
     * @return the exception that says whether the key survives it.
     */
    private fun asFailure(cause: GeneralSecurityException): DeviceKeyException =
        when {
            cause is UnrecoverableKeyException || cause::class.java.name == KEY_INVALIDATED -> discarding(cause)
            else ->
                when (KeystoreFailures.verdictFor(cause, PlatformKeystoreReading)) {
                    KeystoreVerdict.UNAVAILABLE -> DeviceKeyException.CryptoUnavailable(cause)
                    KeystoreVerdict.KEY_GONE -> discarding(cause)
                    KeystoreVerdict.DEFECT -> DeviceKeyException.SigningFailed(cause)
                }
        }

    /**
     * Reports the key gone, having removed the alias that named it. A failed removal is reported as the
     * platform being unavailable instead: the cleanup did not happen, so claiming it would be false.
     *
     * @param cause the failure that proved the key unusable.
     * @return KeyLost when the alias was removed, CryptoUnavailable when the removal failed.
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
            readPrivateKey(keyStore(), alias) as? PrivateKey
        } catch (e: UnrecoverableKeyException) {
            throw asFailure(e)
        } catch (e: GeneralSecurityException) {
            throw DeviceKeyException.CryptoUnavailable(e)
        } catch (e: ProviderException) {
            throw asProviderFailure(e)
        }

    /**
     * The public half, read from the entry's self-signed certificate — the only way to reach it, because
     * `getKey` answers with the private half alone.
     *
     * @return the public half, or null when the alias holds no entry.
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
     * StrongBox where the platform has it, otherwise whatever the platform gives. The level obtained is
     * logged rather than enforced: production accepts what it gets, and the manual device tier is where
     * hardware backing is asserted.
     *
     * @return the private half of the generated keypair.
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
     * No `setUserAuthenticationRequired`: this key signs during background work with nobody present. The
     * storage key omits it for the same reason. `setDigests(DIGEST_SHA256)` is not decoration: a key
     * generated without it is refused at `initSign`, a signing error on a never-usable key.
     *
     * @return the generator's base spec, at the fixed alias.
     */
    private fun baseSpec(): KeyGenParameterSpec.Builder =
        KeyGenParameterSpec
            .Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)

    /**
     * Best effort, for the log line only. `getSecurityLevel` is API 31; below that the answer is coarser.
     *
     * @param key the key whose backing is reported.
     * @return the level by name, or "unknown" when the platform cannot say.
     */
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
         *
         * Both hold **within one process only**, which is the scope this SDK synchronises at throughout. See
         * the class for what that leaves open.
         */
        private val MONITOR = Any()

        const val PROVIDER = "AndroidKeyStore"
        const val CURVE = "secp256r1"

        const val KEY_INVALIDATED = "android.security.keystore.KeyPermanentlyInvalidatedException"
    }
}
