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
}
