package com.payabli.sdk.core.storage

import androidx.annotation.RestrictTo

/**
 * Failures from [PayabliSecureStorage].
 *
 * Storage-local rather than a new `PayabliErrorCode` case, mirroring iOS's own `KeychainError`: the
 * shared error taxonomy is a cross-platform surface and a storage primitive does not widen it.
 *
 * **Blast radius is the distinction that matters.** [KeyInvalidated] means nothing in the store can be
 * read; [ValueUnreadable] means one entry cannot. Collapsing the two leaves a caller unable to tell
 * "re-authenticate" from "re-obtain this value", and storage unable to choose what to discard.
 *
 * No message here carries a stored value.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public sealed class SecureStorageException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * The encryption key is absent or unusable, so every value is unrecoverable.
     *
     * The store has been cleared by the time this is thrown: the remaining blobs were sealed under the
     * key that is gone, so keeping them would fail every later read and let a new write mix a fresh key
     * with stale ciphertext. The key is not regenerated here; the next write creates one.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class KeyInvalidated(
        cause: Throwable? = null,
    ) : SecureStorageException(
            "the storage key is gone; every stored value was discarded and re-authentication is required",
            cause,
        )

    /**
     * One stored value failed authentication while the key is still usable, and has been discarded.
     *
     * Causes are a partial write, a bit flip, a hand edit, or a rotated key, which is indistinguishable
     * from corruption on the read side. The rest of the store is intact.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class ValueUnreadable(
        cause: Throwable? = null,
    ) : SecureStorageException("the stored value could not be authenticated and was discarded", cause)

    /** The Keystore or the cipher failed for a reason that is neither key loss nor a bad value. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class CryptoUnavailable(
        cause: Throwable? = null,
    ) : SecureStorageException("the platform key store or cipher is unavailable", cause)

    /**
     * The backing file could not be read or written, **or** a stored blob is not a well-formed envelope.
     *
     * Both, and the message says both, because the second raises this while the file was read perfectly well: a
     * blob that fails base64 decoding, or is shorter than an IV plus a tag, is corruption rather than an I/O
     * failure, and a message naming only the file sends a caller looking for a disk problem that did not happen.
     *
     * Note what does **not** raise this: a whole store whose JSON cannot be parsed is reset and read as empty,
     * because refusing to load would make one bad write permanent.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class StorageUnavailable(
        cause: Throwable? = null,
    ) : SecureStorageException(
            "the secure storage file could not be read or written, or a stored value is malformed",
            cause,
        )
}
