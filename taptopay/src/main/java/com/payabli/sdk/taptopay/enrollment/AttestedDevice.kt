package com.payabli.sdk.taptopay.enrollment

import kotlinx.serialization.Serializable

/**
 * The attestation binding this device holds: which paypoint, which device handle, which key.
 *
 * **This records a fact, never a name.** The fact is that the service holds a binding between this device
 * and this key. There is exactly one device key, at one handle fixed in the SDK, and nothing here records
 * where it lives or how to find it — [keyId] is derived from the key's own public half, not a label pointing
 * at it. Do not add an alias, a slot name, or anything that would have to be enumerated to recover.
 *
 * That distinction is what makes losing this record cheap. A lost *name* strands a key that nothing can name
 * and nothing can delete. A lost *fact* costs one redundant attestation: the next run presents the key
 * already sitting at the handle, the service revokes the prior binding and inserts a new one, and the device
 * is where it was.
 *
 * **Whether the device is activated is not here, and must not be added.** Activation is the service's state,
 * it changes without this SDK being involved, and a copy of it here would be a claim nobody re-checks. The
 * sibling SDK keeps the same three values and no more, and answers activation from a live call. Android has
 * no status route yet, so until one exists the answer comes from the run that asked — see [EnrollmentOutcome]
 * — and a warm start makes no claim at all.
 *
 * **A cache, not a credential.** Every field is identity; none is secret. Forging one gets a device nothing,
 * because the authority is the service's own row plus a key that cannot leave the platform's key store. A
 * record copied from another device names a key this one does not hold, which [DeviceEnrollment] rejects
 * before it reaches the network.
 *
 * Not a data class: a generated `toString` would print all three, and [entry] names a merchant.
 */
@Serializable
internal class AttestedDevice(
    /**
     * The paypoint this binding is against.
     *
     * The service scopes a device by paypoint, so a record made under one entry says nothing about another.
     * A session re-initialized against a different configuration must not read this as its own.
     */
    val entry: String,
    /** The service's handle for this device, from `/register`. Not derivable; this is the reason to persist. */
    val deviceId: String,
    /**
     * The thumbprint of the key that was attested.
     *
     * Compared against the key currently at the handle before this record is trusted. Without it, a key the
     * platform re-created would reach `/activate` and come back as a revoked attestation — a true-ish answer
     * arrived at through the revocation path, which means something else entirely and would be read that way
     * by whoever is looking at it.
     */
    val keyId: String,
) {
    /** All three are identity, and [entry] names a merchant. */
    override fun toString(): String = "AttestedDevice()"
}
