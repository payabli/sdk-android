package com.payabli.sdk.core.storage

import androidx.annotation.RestrictTo

/**
 * A key/value store whose values are encrypted at rest.
 *
 * Free of `android.*` imports on purpose, so a consumer can be unit-tested against
 * [com.payabli.sdk.core.storage.InMemorySecureStorage] with no device and no Keystore. The Android
 * implementation lives in `impl` and supplies the platform dependency.
 *
 * **Keys are names, values are secrets.** A key appears in plaintext in the backing file and may be
 * logged; a value is encrypted and must never be. Callers pick stable, non-sensitive key names.
 *
 * Suspending, which is where this deliberately differs from the iOS counterpart's synchronous
 * protocol. Keychain reads are synchronous, whereas this reads a file, and SEC-001 Section 9.1 names
 * synchronous I/O on the calling thread as one of the reasons `EncryptedSharedPreferences` was
 * rejected. Making it suspend is the point rather than an inconvenience: it cannot be called from the
 * main thread by accident.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface PayabliSecureStorage {
    /**
     * The stored value, or null when nothing is stored under [key].
     *
     * Throws [SecureStorageException.KeyInvalidated] when the encryption key is gone and the stored
     * bytes can therefore never be read again. That is a distinct outcome from absence and must not be
     * folded into null: absence means "nothing was stored", invalidation means "something was stored,
     * it is unrecoverable, and the caller has to re-authenticate". SEC-001 Section 9.3.
     */
    public suspend fun get(key: String): String?

    /** Stores [value] under [key], replacing any previous value. */
    public suspend fun set(
        key: String,
        value: String,
    )

    /** Removes [key]. Succeeds whether or not anything was stored. */
    public suspend fun remove(key: String)
}
