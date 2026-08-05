package com.payabli.sdk.core.devicekey

import androidx.annotation.RestrictTo

/**
 * Failures from [DeviceKey].
 *
 * Key-local rather than a new `PayabliErrorCode` case, on the same grounds the storage failures are: the
 * shared error taxonomy is a cross-platform surface, and a device-key primitive does not widen it.
 *
 * **The distinction that matters is whether the key is gone.** [KeyLost] means the attested key no longer
 * exists, so nothing it signed can be proven again and the device has to be attested afresh; every later
 * call fails the same way until it is. [SigningFailed] means one signature attempt did not complete while
 * the key is still there, so a caller may try again. Collapsing the two leaves a caller retrying a device
 * that will never recover, or re-attesting one that only hit a transient failure.
 *
 * No message here carries key material or a device identifier.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public sealed class DeviceKeyException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * The key is absent or unusable, so the device must be attested again under a new one.
     *
     * The alias has been discarded by the time this is thrown wherever discarding it could succeed: leaving
     * an unusable alias in place would make every later signature fail identically with nothing able to
     * clear it.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class KeyLost(
        cause: Throwable? = null,
    ) : DeviceKeyException(
            "the device key is gone; the device must be attested again before it can be used",
            cause,
        )

    /** One signature did not complete while the key itself is still usable. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class SigningFailed(
        cause: Throwable? = null,
    ) : DeviceKeyException("the device key could not sign this payload", cause)

    /** The platform key store is unavailable, which says nothing about whether the key survives. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class CryptoUnavailable(
        cause: Throwable? = null,
    ) : DeviceKeyException("the platform key store is unavailable", cause)
}
