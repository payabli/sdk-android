package com.payabli.sdk.taptopay.enrollment

import kotlinx.serialization.Serializable

/**
 * What the service said about this device, last time it said anything.
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
 * **A cache, not a credential.** Every field is identity or a status echo; none is secret. Forging one gets
 * a device nothing, because the authority is the service's own row plus a key that cannot leave the
 * platform's key store. Setting [activated] by hand activates nothing — the next call fails against the
 * service's state instead. A record copied from another device names a key this one does not hold, which
 * [DeviceEnrollment] rejects before it reaches the network.
 *
 * Not a data class: a generated `toString` would print [deviceId], [keyId] and [entry], and the last of
 * those names a merchant.
 */
@Serializable
internal class AttestedDevice(
    /**
     * The paypoint this binding is against.
     *
     * Stored because the service scopes a device by paypoint, so a record made under one entry says nothing
     * about another. A session re-initialised against a different configuration must not read this as its
     * own.
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
    /**
     * Whether the service last reported this device as active.
     *
     * A cache of the service's answer, and it can go stale: nothing in this module's routes reports device
     * status, so a device retired out of band still reads as active here until a call says otherwise. Kept
     * anyway, because the alternative is prompting an already-active merchant for a code on every launch.
     */
    val activated: Boolean,
) {
    /** With activation recorded. */
    fun activated(): AttestedDevice = AttestedDevice(entry, deviceId, keyId, activated = true)

    /** Three of the four are identity, and [entry] names a merchant. */
    override fun toString(): String = "AttestedDevice(activated=$activated)"
}
