package com.payabli.sdk.core.storage

import androidx.annotation.RestrictTo

/**
 * A key/value store whose values are encrypted at rest.
 *
 * Free of `android.*` imports, so a consumer can be unit-tested against [InMemorySecureStorage] with no
 * device. The Android implementation lives in `impl`.
 *
 * **Keys are names, values are secrets.** A key is stored in plaintext and may be logged; a value is
 * encrypted and must never be.
 *
 * Values are `CharArray` so they can be overwritten after use, which a `String` cannot be. This store
 * wipes every buffer it creates internally; it does not touch the array passed to [set], and cannot wipe
 * what [get] returns, so clear those yourself.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface PayabliSecureStorage {
    /**
     * The stored value as a fresh array, or null when nothing is stored under [key].
     *
     * Null means nothing was ever stored, and is distinct from the two failures, which differ in blast
     * radius: [SecureStorageException.KeyInvalidated] means the store was cleared and the caller must
     * re-authenticate, [SecureStorageException.ValueUnreadable] means this entry alone was discarded.
     */
    public suspend fun get(key: String): CharArray?

    /** Stores [value] under [key], replacing any previous value. [value] is not cleared by this call. */
    public suspend fun set(
        key: String,
        value: CharArray,
    )

    /** Removes [key]. Succeeds whether or not anything was stored. */
    public suspend fun remove(key: String)
}
