package com.payabli.sdk.core.storage

import androidx.annotation.RestrictTo

/**
 * Failures from [PayabliSecureStorage].
 *
 * A storage-local hierarchy rather than a new `PayabliErrorCode` case, mirroring iOS, whose
 * `KeychainStorage` declares its own `KeychainError` instead of extending the shared error enum. The
 * shared taxonomy is a settled cross-platform surface and a storage primitive is not the place to
 * widen it unilaterally; whichever layer surfaces these to a host maps them there.
 *
 * No message here ever carries a stored value. A decryption failure that echoed its input would put
 * the secret in a log line, which is the one thing this class exists to prevent.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public sealed class SecureStorageException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * The encryption key no longer exists or can no longer be used, so anything already written under
     * it is unrecoverable.
     *
     * A lifecycle event, not a defect: SEC-001 Section 9.3 requires regenerating the key and requiring
     * re-authentication or re-enrollment. The implementation has already regenerated the key and
     * discarded the unreadable bytes by the time this is thrown, so a caller's job is to re-authenticate
     * rather than to repair anything.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class KeyInvalidated(
        cause: Throwable? = null,
    ) : SecureStorageException("the storage key was invalidated; stored values are unrecoverable", cause)

    /** The Keystore or the cipher failed for a reason that is not key invalidation. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class CryptoUnavailable(
        cause: Throwable? = null,
    ) : SecureStorageException("the platform key store or cipher is unavailable", cause)

    /** Reading or writing the backing file failed. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class StorageUnavailable(
        cause: Throwable? = null,
    ) : SecureStorageException("the secure storage file could not be read or written", cause)
}
