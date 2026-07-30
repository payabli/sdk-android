package com.payabli.sdk.core.storage.impl

/**
 * Encrypts and decrypts one value, as an opaque text blob suitable for storing in a file.
 *
 * A seam rather than a layer for its own sake. The Android implementation needs a Keystore and
 * therefore a device, while the file handling around it, atomic replace, concurrent writes, removal and
 * corrupt input, is ordinary logic that should not require an emulator to test. Splitting here is what
 * lets the persistence half be covered by JVM unit tests and the crypto half by an instrumented one.
 */
internal interface ValueCipher {
    /** Returns an opaque blob. Implementations must produce a different blob each call for equal input. */
    fun encrypt(plaintext: String): String

    /** Reverses [encrypt]. */
    fun decrypt(blob: String): String
}
