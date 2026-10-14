package com.payabli.sdk.core.storage.impl

import com.payabli.sdk.core.storage.SecureStorageException
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * One answer to "which store is this": the lock, the Keystore alias and the temporary-file prefix
 * all read the canonical path's identity, and there is no fallback — an unresolvable path is a
 * storage failure. A moved store is a different store: its blobs read as KeyInvalidated.
 */
internal object StoreIdentity {
    /**
     * Hex of the first [IDENTITY_BYTES] bytes of the canonical path's SHA-256, or a failure. Truncated
     * because the full digest buys nothing: the bar is accidental collision across a handful of stores,
     * and 128 bits clears it by an enormous margin.
     *
     * @param file the backing file whose canonical path names the store.
     * @return hex of the first [IDENTITY_BYTES] bytes of the canonical path's SHA-256.
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
