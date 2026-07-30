package com.payabli.sdk.core.storage.platform

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.annotation.RequiresApi
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.PayabliLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.core.storage.impl.ValueCipher
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM under a key held in the Android Keystore. Only ciphertext leaves this class.
 *
 * In `platform` because Keystore and `android.util.Base64` have no JVM implementation, so no unit test can
 * reach a line of this file and the instrumented suite is what covers it.
 *
 * `androidx.security:security-crypto` is deliberately absent: it was deprecated in 2025 in favour of the
 * platform APIs used here.
 *
 * The per-write IV comes from Keystore rather than from us. Randomized encryption is required on the key,
 * which makes supplying an IV an error and has the platform generate a fresh one per operation, so IV
 * freshness holds by construction.
 */
internal class KeystoreValueCipher(
    private val keyAlias: String,
    private val logger: PayabliLogger,
) : ValueCipher {
    override fun encrypt(
        aad: String,
        plaintext: ByteArray,
    ): String {
        val cipher = cipher()
        try {
            // No IvParameterSpec: randomized encryption is required, so the platform supplies the IV.
            cipher.init(Cipher.ENCRYPT_MODE, keyForWriting())
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            require(iv.size == IV_BYTES) { "expected a $IV_BYTES-byte GCM IV, got ${iv.size}" }
            return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        } catch (e: GeneralSecurityException) {
            throw asFailure(e)
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
                throw SecureStorageException.StorageUnavailable(e)
            }
        // IV plus tag, not just IV. A blob between the two lengths is valid base64 and would otherwise
        // reach doFinal and be reported as a tag failure, which is corruption misreported as a bad value.
        if (bytes.size < IV_BYTES + TAG_BYTES) throw SecureStorageException.StorageUnavailable()

        try {
            val cipher = cipher()
            cipher.init(Cipher.DECRYPT_MODE, keyForReading(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            throw asFailure(e)
        }
    }

    /**
     * Whether the alias is present, so a write can tell a fresh install from a lost key.
     *
     * Without it both look identical from the write side and [keyForWriting] mints a replacement for either,
     * which silently strands every value already on disk under the key that is gone.
     */
    override fun hasKey(): Boolean = existingKey() != null

    /**
     * Maps a platform failure by **blast radius**, which is the whole point of the split.
     *
     * A tag failure means this one blob cannot be authenticated under a key that is otherwise fine, so it
     * must not delete the alias: every value shares one, and doing so made a single damaged entry destroy
     * all the others. Only the two causes that prove the key itself is unusable discard the alias, and
     * there it is required, because otherwise every later write fails the same way.
     *
     * `KeyPermanentlyInvalidatedException` is matched by name rather than type: it extends
     * `InvalidKeyException`, so a reordered catch block would silently demote it to a generic failure.
     */
    private fun asFailure(cause: GeneralSecurityException): SecureStorageException =
        when {
            cause is AEADBadTagException -> SecureStorageException.ValueUnreadable(cause)

            cause is UnrecoverableKeyException || cause::class.java.name == KEY_INVALIDATED -> {
                logger.warn(LogField.safe("keyAlias", keyAlias)) { "storage key unusable, discarding alias" }
                runCatching { keyStore().deleteEntry(keyAlias) }
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

    /**
     * Writing may create the key: a first write on a fresh install has nothing to reuse.
     *
     * Double-checked under a per-alias monitor, because the alias is shared more widely than any one store's
     * lock. `PayabliSecureStorages.create` takes `fileName` while defaulting `keyAlias`, so two stores over
     * different files legitimately share an alias, and their locks are keyed by path. Left unsynchronized, both
     * first writes see no key and both generate: the second generation replaces the first key and the first
     * store's already-written blob becomes permanently unreadable, reported later as a corrupt value on
     * something that was never corrupt.
     *
     * The window that remains is harmless. A store that sees no key while a sibling creates one encrypts under
     * the sibling's key, and nothing is lost, because there is only ever one key per alias. Two *creations* are
     * the loss, and that is what this removes.
     */
    private fun keyForWriting(): SecretKey =
        existingKey() ?: synchronized(monitorFor(keyAlias)) { existingKey() ?: createKey() }

    /**
     * Reading may not create one, and that is the correctness of the invalidation path: a key minted here
     * turns a lost key into a tag failure, which reports a bad value instead of a lost store.
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

    /**
     * StrongBox where the platform has it, otherwise whatever the platform gives.
     *
     * The level obtained is logged rather than enforced. Requiring hardware would fail on emulators and on
     * devices without a TEE, so production accepts what it gets and the manual device tier is where
     * hardware backing is actually asserted.
     */
    private fun createKey(): SecretKey {
        // StrongBox is API 28 while this module's floor is 23: unguarded, this is a NoSuchMethodError on
        // 23 to 27, which no test device would surface because they are all newer.
        val key =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                strongBoxKey() ?: generate(baseSpec().build())
            } else {
                generate(baseSpec().build())
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
     */
    private fun baseSpec(): KeyGenParameterSpec.Builder =
        KeyGenParameterSpec
            .Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)

    /** Best effort, for the log line only. `getSecurityLevel` is API 31; below that the answer is coarser. */
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
