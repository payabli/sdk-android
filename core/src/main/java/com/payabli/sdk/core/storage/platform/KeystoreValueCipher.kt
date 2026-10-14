package com.payabli.sdk.core.storage.platform

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.annotation.RequiresApi
import com.payabli.sdk.core.keystore.KeystoreFailures
import com.payabli.sdk.core.keystore.KeystoreVerdict
import com.payabli.sdk.core.keystore.platform.PlatformKeystoreReading
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.core.storage.impl.ValueCipher
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM under a key held in the Android Keystore. Only ciphertext leaves this class. In `platform`
 * because Keystore and `android.util.Base64` have no JVM implementation. The per-write IV comes from
 * Keystore: randomized encryption is required on the key, so IV freshness holds by construction.
 *
 * @property beforeKeyGeneration a test seam, no-op in production: a rendezvous opening the generation race for the test that asserts it.
 */
internal class KeystoreValueCipher(
    private val keyAlias: String,
    private val logger: SdkLogger,
    private val beforeKeyGeneration: () -> Unit = {},
) : ValueCipher {
    override fun encrypt(
        aad: String,
        plaintext: ByteArray,
    ): String {
        val cipher = cipher()
        try {
            // No IvParameterSpec: randomized encryption is required, so the platform supplies the IV.
            // keyForReading, not a creating variant: provisioning is ensureKey's job alone, so an alias
            // that vanished after provisioning is reported instead of silently replaced.
            cipher.init(Cipher.ENCRYPT_MODE, keyForReading())
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            require(iv.size == IV_BYTES) { "expected a $IV_BYTES-byte GCM IV, got ${iv.size}" }
            return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        } catch (e: GeneralSecurityException) {
            throw asFailure(e)
        } catch (e: ProviderException) {
            throw asProviderFailure(e)
        }
    }

    override fun decrypt(
        aad: String,
        blob: String,
    ): ByteArray {
        val bytes =
            try {
                Base64.decode(blob, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                throw SecureStorageException.ValueUnreadable(e)
            }
        // A malformed envelope fails this way on every read, so it is unreadable and discarded rather than
        // retryable. IV plus tag, not just IV, so a short blob never reaches doFinal.
        if (bytes.size < IV_BYTES + TAG_BYTES) throw SecureStorageException.ValueUnreadable()

        try {
            val cipher = cipher()
            cipher.init(Cipher.DECRYPT_MODE, keyForReading(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            throw asFailure(e)
        } catch (e: ProviderException) {
            throw asProviderFailure(e)
        }
    }

    /**
     * Provisions the alias, and is the only place a key is ever created, double-checked under the
     * per-alias monitor. With [mayCreate] false it proves a key is *present*, not that it is the one
     * that sealed the store: continuity comes from alias ownership, which `SecureStorageFactory.create` holds.
     *
     * @param mayCreate whether a missing key is created; false only proves one is present.
     */
    override fun ensureKey(mayCreate: Boolean) {
        if (existingKey() != null) return
        beforeKeyGeneration()
        synchronized(monitorFor(keyAlias)) {
            if (existingKey() != null) return
            if (!mayCreate) throw SecureStorageException.KeyInvalidated()
            createKey()
        }
    }

    /**
     * A Keystore backend failure, outside the checked hierarchy everything else here maps: without this,
     * a keystore daemon that is unreachable escapes the [SecureStorageException] surface as a raw
     * runtime failure. Always CryptoUnavailable, and not mapped inside [generate].
     *
     * @param cause the backend failure the platform raised.
     * @return a CryptoUnavailable carrying the cause.
     */
    private fun asProviderFailure(cause: ProviderException): SecureStorageException =
        SecureStorageException.CryptoUnavailable(cause)

    /**
     * Maps a platform failure by **blast radius**: a tag failure means this one blob cannot be
     * authenticated under a key that is otherwise fine, so it must not delete the alias; only causes
     * that prove the key itself is unusable discard it. Key-invalidated is matched by name, not type.
     *
     * @param cause the platform failure the cipher path raised.
     * @return the exception that says what the failure costs.
     */
    private fun asFailure(cause: GeneralSecurityException): SecureStorageException =
        when {
            cause is AEADBadTagException -> SecureStorageException.ValueUnreadable(cause)
            cause is UnrecoverableKeyException || cause::class.java.name == KEY_INVALIDATED -> discarding(cause)
            else ->
                when (KeystoreFailures.verdictFor(cause, PlatformKeystoreReading)) {
                    KeystoreVerdict.UNAVAILABLE -> SecureStorageException.CryptoUnavailable(cause)
                    KeystoreVerdict.KEY_GONE -> discarding(cause)
                    KeystoreVerdict.DEFECT -> SecureStorageException.CipherFailed(cause)
                }
        }

    private fun discarding(cause: Throwable): SecureStorageException {
        logger.warn(LogField.safe("keyAlias", keyAlias)) { "storage key unusable, discarding alias" }
        return if (runCatching { keyStore().deleteEntry(keyAlias) }.isSuccess) {
            SecureStorageException.KeyInvalidated(cause)
        } else {
            // The cleanup KeyInvalidated promises did not happen, so promising it would be false: every
            // later write would meet the same unusable alias. CryptoUnavailable is the honest answer, and
            // it also leaves the store intact rather than clearing blobs that may yet be readable.
            SecureStorageException.CryptoUnavailable(cause)
        }
    }

    private fun cipher(): Cipher =
        try {
            Cipher.getInstance(TRANSFORMATION)
        } catch (e: GeneralSecurityException) {
            throw asFailure(e)
        } catch (e: ProviderException) {
            throw asProviderFailure(e)
        }

    private fun keyStore(): KeyStore =
        try {
            KeyStore.getInstance(PROVIDER).apply { load(null) }
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException.CryptoUnavailable(e)
        } catch (e: java.io.IOException) {
            throw SecureStorageException.CryptoUnavailable(e)
        } catch (e: ProviderException) {
            throw asProviderFailure(e)
        }

    /**
     * Reading may not create one, and that is the correctness of the invalidation path: a key minted here
     * turns a lost key into a tag failure, which reports a bad value instead of a lost store.
     *
     * @return the existing key, or KeyInvalidated when the alias holds none.
     */
    private fun keyForReading(): SecretKey = existingKey() ?: throw SecureStorageException.KeyInvalidated()

    private fun existingKey(): SecretKey? =
        try {
            keyStore().getKey(keyAlias, null) as? SecretKey
        } catch (e: UnrecoverableKeyException) {
            throw asFailure(e)
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException.CryptoUnavailable(e)
        } catch (e: ProviderException) {
            throw asProviderFailure(e)
        }

    /**
     * StrongBox where the platform has it, otherwise whatever the platform gives. The level obtained is
     * logged rather than enforced: requiring hardware would fail on emulators, and the manual device
     * tier is where hardware backing is actually asserted.
     *
     * @return the generated secret key.
     */
    private fun createKey(): SecretKey {
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
                // catching it there would swallow the signal strongBoxKey() falls back on. See asProviderFailure.
                throw asProviderFailure(e)
            }
        logger.debug(LogField.safe("securityLevel", securityLevelOf(key))) { "storage key created" }
        return key
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun strongBoxKey(): SecretKey? =
        try {
            generate(baseSpec().setIsStrongBoxBacked(true).build())
        } catch (_: StrongBoxUnavailableException) {
            null
        }

    /**
     * No `setUserAuthenticationRequired`: the first consumer is a secret read during background token
     * refresh with nobody present, and binding the key to user presence would make that impossible.
     *
     * @return the generator's base spec, at this store's alias.
     */
    private fun baseSpec(): KeyGenParameterSpec.Builder =
        KeyGenParameterSpec
            .Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)

    /**
     * Best effort, for the log line only. `getSecurityLevel` is API 31; below that the answer is coarser.
     *
     * @param key the key whose backing is reported.
     * @return the level by name, or "unknown" when the platform cannot say.
     */
    private fun securityLevelOf(key: SecretKey): String =
        runCatching {
            val info =
                SecretKeyFactory
                    .getInstance(key.algorithm, PROVIDER)
                    .getKeySpec(key, KeyInfo::class.java) as KeyInfo
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

    private fun generate(spec: KeyGenParameterSpec): SecretKey =
        try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException.CryptoUnavailable(e)
        }

    private companion object {
        /** One monitor per alias, shared by every cipher over it, mirroring `FileSecureStorage`'s path locks. */
        private val monitors = HashMap<String, Any>()

        private fun monitorFor(keyAlias: String): Any =
            // A plain map under a monitor, not ConcurrentHashMap.computeIfAbsent, which is API 24 against this
            // module's floor of 23. Contention is one lookup per first write.
            synchronized(monitors) { monitors.getOrPut(keyAlias) { Any() } }

        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8

        const val KEY_INVALIDATED = "android.security.keystore.KeyPermanentlyInvalidatedException"
    }
}
