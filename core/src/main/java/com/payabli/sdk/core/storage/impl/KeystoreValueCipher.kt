package com.payabli.sdk.core.storage.impl

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.annotation.RequiresApi
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.PayabliLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.storage.SecureStorageException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM under a key held in the Android Keystore. Only ciphertext leaves this class.
 *
 * `androidx.security:security-crypto` is deliberately absent: SEC-001 Section 9.1 rejects it, and
 * Google deprecated the library in 2025 with guidance to use the platform APIs directly.
 *
 * **The IV comes from Keystore, not from us, and that is the stronger guarantee.** A Keystore key is
 * created with randomized encryption required, which makes supplying an IV for encryption an error and
 * makes the platform generate a fresh one per operation. So "a fresh IV per write, never reused under
 * the same key" holds by construction rather than by our discipline, which is better than calling
 * `SecureRandom` ourselves and hoping every future edit remembers to.
 */
internal class KeystoreValueCipher(
    private val keyAlias: String,
    private val logger: PayabliLogger,
) : ValueCipher {
    override fun encrypt(plaintext: String): String {
        val cipher = cipher()
        try {
            // No IvParameterSpec. Randomized encryption is required on the key, so passing one throws and
            // the platform supplies the IV instead; it is read back below.
            cipher.init(Cipher.ENCRYPT_MODE, keyForWriting())
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            require(iv.size == IV_BYTES) { "expected a $IV_BYTES-byte GCM IV, got ${iv.size}" }
            return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        } catch (e: GeneralSecurityException) {
            throw asFailure(e)
        }
    }

    override fun decrypt(blob: String): String {
        val bytes =
            try {
                Base64.decode(blob, Base64.NO_WRAP)
            } catch (e: IllegalArgumentException) {
                // A truncated or hand-edited file, not a key problem, so it must not read as invalidation.
                throw SecureStorageException.StorageUnavailable(e)
            }
        if (bytes.size <= IV_BYTES) throw SecureStorageException.StorageUnavailable()

        try {
            val spec = GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES)
            val cipher = cipher()
            cipher.init(Cipher.DECRYPT_MODE, keyForReading(), spec)
            val plaintext = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
            return String(plaintext, Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw asFailure(e)
        }
    }

    /**
     * Maps a platform failure to the storage taxonomy, and the two invalidation cases are the point.
     *
     * `KeyPermanentlyInvalidatedException` is caught by name rather than by type because it is API 23 and
     * a subclass of `InvalidKeyException`, so an untargeted `GeneralSecurityException` branch would
     * silently swallow it as a generic crypto failure and a caller would never learn to re-authenticate.
     */
    private fun asFailure(cause: GeneralSecurityException): SecureStorageException =
        when {
            // AEADBadTagException included on purpose. It arrives when the bytes cannot be authenticated
            // under the current key, which is what a replaced or rotated key looks like from the read side,
            // and the caller's required response is identical to invalidation: the value is gone, re-obtain it.
            cause is AEADBadTagException ||
                cause is UnrecoverableKeyException ||
                cause::class.java.name == KEY_INVALIDATED -> {
                // Regenerate now so the next write succeeds. The old bytes stay unreadable either way, and
                // leaving a dead alias in place would fail every future call for the same reason.
                logger.warn(LogField.safe("keyAlias", keyAlias)) { "storage key invalidated, regenerating" }
                runCatching { keyStore().deleteEntry(keyAlias) }
                runCatching { createKey() }
                SecureStorageException.KeyInvalidated(cause)
            }

            else -> SecureStorageException.CryptoUnavailable(cause)
        }

    private fun cipher(): Cipher =
        try {
            Cipher.getInstance(TRANSFORMATION)
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException.CryptoUnavailable(e)
        }

    private fun keyStore(): KeyStore =
        try {
            KeyStore.getInstance(PROVIDER).apply { load(null) }
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException.CryptoUnavailable(e)
        } catch (e: java.io.IOException) {
            throw SecureStorageException.CryptoUnavailable(e)
        }

    /** Writing may create the key: a first write on a fresh install has nothing to reuse. */
    private fun keyForWriting(): SecretKey = existingKey() ?: createKey()

    /**
     * Reading may not create it, and that distinction is the whole correctness of the invalidation path.
     *
     * Minting a key here was a real defect rather than a theoretical one. With it, deleting the alias under a
     * stored value produced a fresh key, decryption then failed the GCM tag check, and the caller was told
     * `CryptoUnavailable`, meaning "transient platform problem", when the truth was "the value is gone
     * forever, re-authenticate". An absent alias on the read path is invalidation, and is reported as such.
     */
    private fun keyForReading(): SecretKey = existingKey() ?: throw SecureStorageException.KeyInvalidated()

    private fun existingKey(): SecretKey? =
        try {
            keyStore().getKey(keyAlias, null) as? SecretKey
        } catch (e: UnrecoverableKeyException) {
            throw asFailure(e)
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException.CryptoUnavailable(e)
        }

    /** StrongBox where the platform has it, TEE otherwise, reporting which one was obtained. */
    private fun createKey(): SecretKey {
        // StrongBox is API 28 while this module's floor is 23, so it is attempted only where it exists.
        // Caught by Lint rather than by review: without the guard this is a NoSuchMethodError on API 23 to 27,
        // which no emulator in CI would have surfaced because the test devices are all newer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            strongBoxKey()?.let { return it }
        }
        return generate(baseSpec().build()).also {
            logger.debug(LogField.safe("backing", "tee")) { "storage key created" }
        }
    }

    /**
     * StrongBox if the device has one, null if it does not.
     *
     * Absence is the normal outcome rather than a degraded one, since StrongBox is hardware-dependent, which
     * is why this returns null for the caller to fall back rather than raising anything.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun strongBoxKey(): SecretKey? =
        try {
            generate(baseSpec().setIsStrongBoxBacked(true).build()).also {
                logger.debug(LogField.safe("backing", "strongbox")) { "storage key created" }
            }
        } catch (_: StrongBoxUnavailableException) {
            null
        }

    /**
     * No `setUserAuthenticationRequired`, and that is a decision rather than an omission. The first consumer
     * of this store is the refresh secret at rest, which the SDK has to read while refreshing a token with
     * nobody watching; binding the key to user presence would make background refresh impossible.
     * `KeyPermanentlyInvalidatedException` is still handled, because a key can be lost for other reasons and
     * a store that crashes on it would be worse than one that re-enrolls.
     */
    private fun baseSpec(): KeyGenParameterSpec.Builder =
        KeyGenParameterSpec
            .Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)

    private fun generate(spec: KeyGenParameterSpec): SecretKey =
        try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException.CryptoUnavailable(e)
        }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256

        /** GCM's standard nonce length, and what Keystore produces. */
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        /**
         * Matched by name because the class is API 23 while this module's floor is also 23: importing it
         * is fine, but catching it as a type would require it ahead of `InvalidKeyException`, and a future
         * reorder of catch blocks would quietly downgrade invalidation to a generic crypto failure.
         */
        const val KEY_INVALIDATED = "android.security.keystore.KeyPermanentlyInvalidatedException"
    }
}
