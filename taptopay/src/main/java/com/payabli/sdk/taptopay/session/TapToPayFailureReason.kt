package com.payabli.sdk.taptopay.session

/**
 * Why a session failed, in terms of what can be done about it.
 *
 * A closed set of remedies rather than a description of what went wrong, because the question a host asks a
 * failed session is which repair to offer. Two failures with the same remedy are one member here.
 *
 * The reason is an enum and not the exception. This value is held in a state that is read long after the
 * call that produced it, and a `Throwable` brings a cause chain with it; the decode failures in this module
 * already redact theirs for that reason. The exception still reaches the caller that was waiting, by being
 * thrown. This carries what a later observer needs.
 *
 * There is no member for a reader that could not start. Nothing prepares a reader yet, and a reason nothing
 * can produce is a branch a host writes and never runs.
 */
internal enum class TapToPayFailureReason {
    /**
     * The device's proof of identity is gone or was refused, so the session must be built from the top.
     *
     * The service revoked the attestation, or the credential it was pinned to has moved. Re-initializing
     * does not repair it: that path does not attest.
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
     * The SDK and the service disagree about the contract, or the SDK has a defect.
     *
     * A response that could not be decoded is the common one. A host cannot act on it; it is here so that it
     * is not silently filed under one of the others.
     */
    INTERNAL,
}
