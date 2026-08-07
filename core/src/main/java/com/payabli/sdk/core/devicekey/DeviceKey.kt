package com.payabli.sdk.core.devicekey

import androidx.annotation.RestrictTo

/**
 * The device's own signing key: an EC P-256 keypair whose private half never leaves the platform key store.
 *
 * Held by the core rather than by a capability, because the device key is core identity: the same key backs
 * card-present activation today and is what a device-bound credential is issued against later.
 *
 * **Bytes, not encodings.** [publicKeyPoint] and [sign] return raw bytes, and whatever sends them decides
 * how they are encoded on the wire. A base64 helper here would put one channel's wire format in the core
 * and leave the next one converting away from it.
 *
 * **There is no accessor for the private key, at any visibility.** A caller gets signatures, never the key
 * that produced them, which is the same rule the token holder follows.
 *
 * `@RestrictTo(LIBRARY_GROUP)`: reachable from the SDK's own artifacts, including a card-present capability
 * shipped as its own repository, and a Lint error in a host app's build. A host app has no business signing
 * arbitrary payloads with the device's identity.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface DeviceKey {
    /**
     * The identifier the service records for this key, derived from its public half.
     *
     * For a caller that needs the identity without a signature, which is what registration and enrollment
     * send. A caller that needs both takes them from [sign] instead, so the pair cannot disagree.
     *
     * Per key, so a replacement gets a different one. The alias the key is stored under is fixed and is the
     * same on every install, which is why it cannot serve as this: the service would be unable to tell one
     * install's key from another's, or a key from the one it replaced.
     *
     * Derived on every call rather than held, for the reason nothing else here is cached: the key can be
     * deleted while a caller still holds this object, and a remembered identifier would then name material
     * that is gone.
     *
     * @throws DeviceKeyException if the key is gone or the key store cannot be reached.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun identity(): String

    /**
     * The public point in X9.62 uncompressed form, `0x04 || X || Y`, 65 bytes.
     *
     * @throws DeviceKeyException if the key is gone or the key store cannot be reached.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun publicKeyPoint(): ByteArray

    /**
     * Signs [payload] with `SHA256withECDSA`, returning the signature together with the identity of the key
     * that produced it.
     *
     * **The two are returned together because they must describe one key, and asking for them separately
     * cannot guarantee that.** The signature and the identity come from two reads of the key store, and a
     * replacement landing between them yields a signature by the old key labelled with the new key's
     * identity: the service selects an attestation row by that identity and verifies against a public key
     * the signature was never made with, so the assertion is refused with nothing pointing at the cause.
     * One call, one observation of the key, and no way to write the interleaved version.
     *
     * The signature is DER rather than the raw `R || S` pair, because that is what the verifier expects; the
     * two are the same numbers in different envelopes and are not interchangeable.
     *
     * @throws DeviceKeyException if the key is gone, the key store cannot be reached, or signing fails.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun sign(payload: ByteArray): DeviceSignature

    /**
     * Removes the key, so the next caller that may create one gets a new key at the same alias.
     *
     * For a caller that has read a definitive refusal to bind this key. Not for a failure it could not
     * classify and not for a response reporting the key as already bound: deleting on either destroys a
     * credential that is or may still be live, and the key store cannot tell afterwards that it happened.
     *
     * Distinct from clearing an identity record, which forgets what the service said about the key while
     * leaving the key itself in place.
     *
     * Succeeds when there is no key to remove, so a caller that cannot tell whether an earlier attempt
     * completed can repeat it.
     *
     * @throws DeviceKeyException if the key store cannot be reached, in which case the key is still there.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun delete()
}

/**
 * A signature and the identity of the key that made it, from one observation of that key.
 *
 * Separate values would let a caller pair a signature with an identity taken before or after a replacement.
 * This type exists so that pairing is done once, where the key is read, rather than at every call site.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class DeviceSignature(
    /** The DER ECDSA signature over the payload. */
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public val signature: ByteArray,
    /** The signing key's identity, as [DeviceKey.identity] derives it. */
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public val identity: String,
) {
    /** Never the signature or the identity: both are device identity. */
    override fun toString(): String = "DeviceSignature()"
}
