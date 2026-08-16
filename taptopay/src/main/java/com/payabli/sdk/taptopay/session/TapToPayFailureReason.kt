package com.payabli.sdk.taptopay.session

/**
 * Why a session failed, in terms of what can be done about it.
 *
 * Two failures a host repairs the same way are one member here.
 *
 * An enum, and not the exception: this value is retained in a state read long after the call that produced
 * it, and a `Throwable` carries a cause chain that can hold a response body. The exception still reaches
 * the caller that was waiting, by being thrown.
 */
internal enum class TapToPayFailureReason {
    /**
     * The device's proof of identity is gone or was refused, so the session must be built from the top.
     *
     * The service revoked the attestation, or the credential it was pinned to has moved. A repair does not
     * attest, so it cannot restore this.
     */
    ATTESTATION_REQUIRED,

    /**
     * The paypoint, the device or its gateway is not set up for card-present work.
     *
     * Nothing in the SDK repairs this and repeating the call will not either. It is a change someone makes
     * to the account.
     */
    CONFIGURATION_REJECTED,

    /** The service could not be reached, or failed inside. The same call may succeed later. */
    SERVICE_UNAVAILABLE,

    /**
     * This handset cannot take contactless payments, and no repair reaches that.
     *
     * The only member where the remedy is a different device. It is separate from
     * [CONFIGURATION_REJECTED], which is an account someone can change, and from [SDK_INTERNAL_ERROR], which
     * asks a host to report a defect: the wrong hardware is neither a defect nor a setting.
     */
    DEVICE_INELIGIBLE,

    /**
     * The SDK and the service disagree about the contract, or the SDK has a defect.
     *
     * This side of the wire. A failure inside the service is [SERVICE_UNAVAILABLE], which is why the name
     * says which side: an HTTP 500 is called an internal server error and lands there, not here.
     *
     * A response that could not be decoded is the common one. A host cannot act on it; it is here so that it
     * is not silently filed under one of the others.
     */
    SDK_INTERNAL_ERROR,
}
