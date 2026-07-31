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
import java.security.ProviderException
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
    /**
     * Runs after the unsynchronized presence check and before the guarded generation. **A test seam, no-op in
     * production**, and it exists because the race it opens cannot be reached from outside this class.
     *
     * The hazard is two ciphers over one alias both finding no key and both generating, where the second
     * generation replaces the first key and strands whatever was sealed under it. Reaching it needs both callers
     * past the check before either creates, and from outside [ensureKey] that check and the creation are atomic,
     * so no decorator on [ValueCipher] can interleave them. Two coroutines racing cannot either: measured, a plain
     * `Dispatchers.IO` race detected the missing monitor in 3 of 3 whole-suite runs but only about half of the
     * time in isolation, and a start barrier did not improve it, because the test was observing a *consequence*.
     * A blob is only lost if one store finishes encrypting between the two generations, so tighter overlap puts
     * both generations before either encrypt and loses nothing.
     *
     * With a rendezvous here, the test stops asking whether data was lost and asserts the invariant instead: the
     * key is generated exactly once, counted from the log line [createKey] already emits. That is deterministic.
     *
     * The default makes this dead in production, and the parameter is deliberately not a `@VisibleForTesting`
     * member or a mutable static: it is per-instance and injected, the same shape as `FileSecureStorage`'s
     * `dispatcher`.
     */
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
        } catch (e: ProviderException) {
            throw asProviderFailure(e)
        }
    }

    /**
     * Provisions the alias, and is the only place a key is ever created.
     *
     * Double-checked under the per-alias monitor, so concurrent first writes generate once rather than racing
     * to replace each other's key. An earlier version asked a separate `hasKey()` and let `encrypt` create,
     * which left the decision split across two calls with a window between them.
     *
     * **What this guarantees, and what it does not.** With [mayCreate] false it proves a key is *present*, not
     * that the present key is the one that sealed the store. Continuity comes from alias ownership instead:
     * `PayabliSecureStorages.create` derives one alias per backing file, so nothing else can delete and
     * recreate this one. Two ciphers constructed directly over a single alias, which only internal code can do,
     * are outside that guarantee. Proving continuity rather than owning it would need a canary blob decrypted
     * on every write, and that is deliberately not built.
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
     * A Keystore backend failure, which arrives outside the checked hierarchy everything else here maps.
     *
     * `ProviderException` extends `RuntimeException`, not `GeneralSecurityException`, so without this a
     * keystore daemon that is unreachable, `KeyStoreConnectException` among others, escapes the
     * [SecureStorageException] surface as a raw runtime failure and the caller loses the one distinction the
     * surface exists to make. Always `CryptoUnavailable`: a provider that is broken says nothing about this
     * blob or about the key, so it is neither a tag failure nor key loss.
     *
     * Deliberately **not** mapped inside [generate]. `StrongBoxUnavailableException` is itself a
     * `ProviderException`, so a catch there swallows it, [strongBoxKey] never sees the signal it falls back on,
     * and key creation fails outright on every device without StrongBox. [createKey] maps it one level up,
     * after the fallback has resolved.
     */
    private fun asProviderFailure(cause: ProviderException): SecureStorageException =
        SecureStorageException.CryptoUnavailable(cause)

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
                if (runCatching { keyStore().deleteEntry(keyAlias) }.isSuccess) {
                    SecureStorageException.KeyInvalidated(cause)
                } else {
                    // The cleanup KeyInvalidated promises did not happen, so promising it would be false: every
                    // later write would meet the same unusable alias. CryptoUnavailable is the honest answer, and
                    // it also leaves the store intact rather than clearing blobs that may yet be readable.
                    SecureStorageException.CryptoUnavailable(cause)
                }
            }

            else -> SecureStorageException.CryptoUnavailable(cause)
        }

    private fun cipher(): Cipher =
        try {
            Cipher.getInstance(TRANSFORMATION)
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException.CryptoUnavailable(e)
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
