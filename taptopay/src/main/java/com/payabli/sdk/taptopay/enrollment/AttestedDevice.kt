package com.payabli.sdk.taptopay.enrollment

import kotlinx.serialization.Serializable

/**
 * This device's attestation binding for one entry point: paypoint, handle, and a [keyId] derived from the key itself.
 * Identity, never secret; holds no key name, alias or activation status, so losing it costs one re-attestation.
 * Not a data class: a generated `toString` would print all three, and [entry] names a merchant.
 */
@Serializable
internal class AttestedDevice(
    /**
     * The paypoint this binding is against.
     *
     * A device belongs to one paypoint, so a record made under one entry says nothing about another. A
     * session re-initialized against a different configuration must not read this as its own.
     */
    val entry: String,
    /** The handle this device was registered under. Not derivable; this is the reason to persist. */
    val deviceId: String,
    /**
     * The thumbprint of the key that was attested.
     *
     * Compared against the key currently at the handle before this record is trusted. Without it, a key the
     * platform re-created would reach activation and be refused in a way that reads as something else
     * entirely.
     */
    val keyId: String,
) {
    /** All three are identity, and [entry] names a merchant. */
    override fun toString(): String = "AttestedDevice()"
}
