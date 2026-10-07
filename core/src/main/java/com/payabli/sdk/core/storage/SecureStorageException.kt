package com.payabli.sdk.core.storage

import androidx.annotation.RestrictTo

/**
 * Failures from [PayabliSecureStorage].
 *
 * Storage-local rather than a new `PayabliErrorType` case, mirroring iOS's own `KeychainError`: the
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
     * One stored value failed authentication, or is not a well-formed envelope, while the key is still usable,
     * and has been discarded.
     *
     * Causes are a partial write, a bit flip, a hand edit, or a rotated key, which is indistinguishable
     * from corruption on the read side. Each fails the same way on every read, which is why the value is
     * discarded rather than kept for a retry. The rest of the store is intact.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class ValueUnreadable(
        cause: Throwable? = null,
    ) : SecureStorageException("the stored value could not be read and was discarded", cause)

    /** The Keystore or the cipher did not answer, so a later attempt may succeed. The store is left intact. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class CryptoUnavailable(
        cause: Throwable? = null,
    ) : SecureStorageException("the platform key store or cipher is unavailable", cause)

    /** The platform refused an operation this SDK asked for while the key is still usable, which is a defect. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class CipherFailed(
        cause: Throwable? = null,
    ) : SecureStorageException("the platform refused the cipher operation", cause)

    /**
     * The backing file could not be read or written. The store is left intact.
     *
     * A stored blob that is not a well-formed envelope does not raise this: it fails on every read, so it is
     * [ValueUnreadable]. Nor does a whole store whose JSON cannot be parsed, which is reset and read as empty,
     * because refusing to load would make one bad write permanent.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class StorageUnavailable(
        cause: Throwable? = null,
    ) : SecureStorageException(
            "the secure storage file could not be read or written",
            cause,
        )
}
