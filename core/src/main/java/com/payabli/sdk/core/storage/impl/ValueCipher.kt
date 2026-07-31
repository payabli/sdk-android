package com.payabli.sdk.core.storage.impl

/**
 * Encrypts and decrypts one value, as an opaque text blob suitable for storing in a file.
 *
 * A seam so the file handling around it, atomic replace, concurrent writes, removal and corrupt input,
 * can be unit-tested on the JVM while only the crypto needs a device.
 *
 * **[aad] binds a blob to the entry it belongs to and is not optional.** Every value in a store shares
 * one key, so without it a blob is valid under any name and swapping two blobs in the file returns the
 * wrong secret with the tag check passing. Implementations authenticate [aad] and fail when it differs.
 *
 * Plaintext is `ByteArray` so it can be overwritten. Implementations must not retain it.
 */
internal interface ValueCipher {
    /** An opaque blob binding [plaintext] to [aad], different on every call for equal input. */
    fun encrypt(
        aad: String,
        plaintext: ByteArray,
    ): String

    /** Reverses [encrypt], and fails if [aad] is not the value it was sealed with. */
    fun decrypt(
        aad: String,
        blob: String,
    ): ByteArray

    /**
     * Makes sure a key is available to [encrypt] under, and decides whether creating one is allowed.
     *
     * Provisioning is separate from use, and this is the only operation that may create a key: [encrypt]
     * never does. That is what makes the decision the caller's, since only the caller knows whether the store
     * is empty. Creating a key for a store that already holds entries is always wrong, because the new blob
     * would sit beside ciphertext sealed under the key that is gone with nothing reporting the loss.
     *
     * @param mayCreate true only when the store holds no entries, so a missing key means a fresh install.
     *   With entries present a missing key means the key was lost, and this fails with
     *   `SecureStorageException.KeyInvalidated` rather than papering over it.
     */
    fun ensureKey(mayCreate: Boolean)
}
