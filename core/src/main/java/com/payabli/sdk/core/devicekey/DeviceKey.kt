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
     * The key's alias, which is also the identifier the service records for it.
     *
     * Stable for the life of the key. A rotated key gets a new one, so a caller holding an old value is
     * holding a reference to a key that no longer exists rather than a stale name for the current one.
     */
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public val keyId: String

    /**
     * The public point in X9.62 uncompressed form, `0x04 || X || Y`, 65 bytes.
     *
     * @throws DeviceKeyException if the key is gone or the key store cannot be reached.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun publicKeyPoint(): ByteArray

    /**
     * Signs [payload] with `SHA256withECDSA`, returning the DER signature.
     *
     * DER rather than the raw `R || S` pair, because that is what the verifier expects; the two are the
     * same numbers in different envelopes and are not interchangeable.
     *
     * @throws DeviceKeyException if the key is gone, the key store cannot be reached, or signing fails.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun sign(payload: ByteArray): ByteArray
}
