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
 * A key must be losslessly representable as UTF-8, and one that is not is rejected with
 * `IllegalArgumentException`. It is not decoration: the name is what binds a blob to its entry, and malformed
 * UTF-16 collapses to `?` when encoded, so two different names would authenticate each other's value.
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
     * The stored value as a fresh array, or null when no value is stored under [key].
     *
     * **Null is the current state, not a history.** It says only that nothing is stored under this name now: a
     * value that was written and then removed reads as null, and so does one lost when an unparseable store was
     * reset. A caller cannot tell those apart and should not be written as though it can.
     *
     * Every failure below is distinct from null, and all four subtypes can arrive here. They differ in what the
     * caller should do:
     *
     * | Failure | Means | Caller |
     * |---|---|---|
     * | [SecureStorageException.KeyInvalidated] | the key is gone, so the store was cleared | re-authenticate |
     * | [SecureStorageException.ValueUnreadable] | this entry alone could not be authenticated, and was discarded | re-obtain this value |
     * | [SecureStorageException.CryptoUnavailable] | the platform key store or cipher failed | retry |
     * | [SecureStorageException.StorageUnavailable] | the file could not be read or written, or a stored blob is not a well-formed envelope | retry |
     *
     * The first two are terminal for the data they describe; the last two are not, and the store is left
     * intact for both of them.
     *
     * **Unparseable file *content* is not reported at all.** A store whose JSON cannot be parsed is reset and
     * reads as empty, because refusing to load would make one bad write permanent and everything in the file is
     * ciphertext the caller can obtain again. `StorageUnavailable` covers the file being unreachable and a blob
     * that is too short or not decodable, not a whole-store parse failure.
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
