package com.payabli.sdk.core.storage.impl

import com.payabli.sdk.core.storage.SecureStorageException
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * One answer to "which store is this", derived from the backing file and nothing else.
 *
 * Every consumer used to derive its own: the lock keyed itself by canonical path, the Keystore alias by file
 * name, the temporary-file prefix by file name again. Three derivations meant three different answers, and each
 * carried its own defect. Two stores in different directories with the same file name shared one key alias while
 * holding separate locks, so one could replace the key the other's ciphertext was sealed under. A one-character
 * file name produced a two-character temp prefix, which `File.createTempFile` rejects outright.
 *
 * The canonical path is the identity because it is what the filesystem itself considers one file: `dir/store.json`
 * and `dir/../dir/store.json` are the same store and must not get two aliases.
 *
 * **There is no fallback, deliberately.** Resolving the path is the one step that can fail, and answering with the
 * absolute path instead would hand one store a second identity for as long as the failure lasted.
 *
 * **A moved store is a different store.** Tie the alias to the path and relocating the file orphans its key, so
 * its blobs read as `KeyInvalidated`, the store clears, and the next write provisions again. That is the right
 * outcome for ciphertext the caller can re-obtain, and it is what an uninstall already does, but it is a
 * consequence worth knowing before choosing a directory.
 */
internal object StoreIdentity {
    /**
     * Hex of the first [IDENTITY_BYTES] bytes of the canonical path's SHA-256, or a failure.
     *
     * Truncated because the full digest is 64 characters and buys nothing here. These paths come from the app's
     * own data directory rather than from an attacker, so the bar is accidental collision across a handful of
     * stores, and 128 bits clears it by an enormous margin. Measured, the resulting temporary file name is 60
     * bytes against a `NAME_MAX` of 255 on the emulator and both test phones, which leaves room the full digest
     * would spend for no gain.
     *
     * `MessageDigest` is `java.security`, so this is platform surface rather than a dependency, and it runs on
     * the JVM: this file is unit-testable even though everything that consumes it is not.
     */
    fun of(file: File): String {
        // Failing, not falling back. Canonicalisation touches the filesystem and can fail, and switching to the
        // absolute path on that call would give one store two identities: the alias, the lock and the temp prefix
        // all derive from this, so a later successful call would look for a different key, take a different lock,
        // and stop recognising its own temp files. A path that cannot be resolved is a storage failure.
        val path =
            try {
                file.canonicalPath
            } catch (e: IOException) {
                throw SecureStorageException.StorageUnavailable(e)
            } catch (e: SecurityException) {
                throw SecureStorageException.StorageUnavailable(e)
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        return buildString(IDENTITY_BYTES * 2) {
            for (index in 0 until IDENTITY_BYTES) {
                val byte = digest[index].toInt() and 0xFF
                append(HEX[byte shr 4])
                append(HEX[byte and 0x0F])
            }
        }
    }

    /** 128 bits. Changing this changes every alias and orphans every existing key, so it is not a free knob. */
    const val IDENTITY_BYTES: Int = 16

    private val HEX = "0123456789abcdef".toCharArray()
}
