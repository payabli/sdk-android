package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.core.storage.SecureStorageException

/**
 * What this device holds for one paypoint's Tap to Pay registration.
 *
 * Three answers, not two: a store that refused the read is not a device that registered nothing, and
 * reading it as one reports a passing storage fault as the paypoint's configuration.
 */
internal sealed interface StoredRegistration {
    /** A binding is stored, under the id the service assigned at registration. */
    data class Held(
        val activationId: String,
    ) : StoredRegistration

    /** Nothing is stored. A record that would not decode is discarded on the read and counts as this. */
    data object None : StoredRegistration

    /** The store refused the read with [refusal]. */
    class Unreadable(
        val refusal: SecureStorageException,
    ) : StoredRegistration
}
