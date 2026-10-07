package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.core.storage.SecureStorageException

/**
 * What this device holds for one paypoint's Tap to Pay registration.
 *
 * A refused read is its own answer, so a passing storage fault is never reported as the paypoint's
 * configuration.
 */
internal sealed interface StoredRegistration {
    /** A binding is stored, under the id the service assigned at registration. */
    data class Held(
        val activationId: String,
    ) : StoredRegistration {
        override fun toString(): String = "Held"
    }

    /** Nothing is stored. A record that would not decode is discarded on the read and counts as this. */
    data object None : StoredRegistration

    /** The store refused the read with [refusal]. */
    class Unreadable(
        val refusal: SecureStorageException,
    ) : StoredRegistration
}
