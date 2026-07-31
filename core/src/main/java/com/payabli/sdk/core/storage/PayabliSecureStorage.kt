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
 * **Values are bytes, and this store does not interpret them.** Whatever it is handed comes back byte for
 * byte, so nothing here can alter a secret in transit. An earlier revision took `CharArray` and encoded to
 * UTF-8 internally, which silently replaced malformed input: a lone `'\uD800'` was stored as `?` and read
 * back as `?`. Encoding is a property of what a value *means*, which only the caller knows, so it belongs at
 * the caller's boundary. A caller holding text encodes there, and validates there, because a store that
 * transforms its input is a store that can corrupt it.
 *
 * `ByteArray` is as overwritable as `CharArray` was, which is the reason neither is a `String`. Nothing here
 * copies a value, so there is no internal buffer to wipe; it does not touch the array passed to [set], and
 * cannot wipe what [get] returns. **Overwrite both yourself once done, with `fill(0)`.**
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface PayabliSecureStorage {
    /**
     * The stored value as a fresh array, or null when nothing is stored under [key].
     *
     * Null means nothing was ever stored, which is distinct from every failure below. All four subtypes can
     * arrive here, and they differ in what the caller should do about them:
     *
     * | Failure | Means | Caller |
     * |---|---|---|
     * | [SecureStorageException.KeyInvalidated] | the key is gone, so the store was cleared | re-authenticate |
     * | [SecureStorageException.ValueUnreadable] | this entry alone could not be authenticated, and was discarded | re-obtain this value |
     * | [SecureStorageException.CryptoUnavailable] | the platform key store or cipher failed | retry |
     * | [SecureStorageException.StorageUnavailable] | the backing file could not be read, or is malformed | retry |
     *
     * The first two are terminal for the data they describe; the last two are not, and the store is left
     * intact for both of them.
     */
    public suspend fun get(key: String): ByteArray?

    /**
     * Stores [value] under [key], replacing any previous value. [value] is not cleared by this call.
     *
     * Any byte sequence is storable. There is no encoding requirement, because there is no encoding step.
     */
    public suspend fun set(
        key: String,
        value: ByteArray,
    )

    /** Removes [key]. Succeeds whether or not anything was stored. */
    public suspend fun remove(key: String)
}
