package com.payabli.sdk.taptopay.attestation.device

private const val FIRST_PRINTABLE = ' '
private const val LAST_PRINTABLE = '~'

/**
 * What identifies this device to the service: the handle it was registered under and its key.
 *
 * The three travel together and are required together by `/attest`, which is why they are one parameter rather
 * than three. [deviceId] comes from `/register`; [keyId] and [publicKey] come from the device key.
 *
 * Grouping them also removes a hazard the call site had: five same-typed strings in a row, where a transposed
 * pair compiles silently and fails as an attestation the service cannot verify. Three of them are now named
 * once, here, instead of at every call.
 */
internal class DeviceIdentity(
    val deviceId: String,
    /**
     * The device key's identifier: the thumbprint of its public half, per key.
     *
     * Not the alias the key is stored under. That is fixed and identical on every install, so persisting or
     * reconstructing it as this value would name every device's key the same thing.
     */
    val keyId: String,
    /** Base64 of the 65-byte X9.62 uncompressed EC point. Required on this platform. */
    val publicKey: String,
) {
    /** Never a value: all three are device identity or key material. */
    override fun toString(): String = "DeviceIdentity()"
}

/**
 * The proof-of-possession headers `/activate` requires, and `/config` after it.
 *
 * What is verified is a signature over [timestamp] and nothing else, so **[timestamp] must be the exact string
 * the signer signed, byte for byte.** That is why it is carried as a string rather than an instant formatted
 * here: a second formatting of the same moment can differ in fractional digits or offset spelling, and the
 * failure would surface as a signature mismatch with nothing pointing at the cause. The shape is ISO-8601 with
 * fractional seconds, and an assertion is short-lived, so one is minted per call and never cached.
 *
 * This type only carries the values. [DeviceAssertionSigner] produces them from the device key.
 *
 * Values are checked for header safety at construction. `HttpURLConnection.setRequestProperty` rejects an
 * illegal character with an unchecked `IllegalArgumentException` that escapes before the transport can map
 * it, so a caller would see the wrong exception type for the wrong reason; a carriage return or line feed
 * would additionally be header injection. `:core` makes the same check where a bearer token enters, but its
 * helper is `internal` to that module, so this is the same rule stated again rather than a second rule.
 */
internal class DeviceAssertion(
    /** Base64 of the DER ECDSA signature over `UTF8(timestamp)`, which the algorithm hashes once. */
    val assertion: String,
    /**
     * The signing key's identifier, matched against the attestation this device made. Derived from the key,
     * not from its alias.
     */
    val keyId: String,
    val deviceId: String,
    /** The signed timestamp, verbatim. */
    val timestamp: String,
) {
    init {
        requireUsable(assertion, "assertion")
        requireUsable(keyId, "keyId")
        requireUsable(deviceId, "deviceId")
        requireUsable(timestamp, "timestamp")
    }

    /**
     * The four headers, ready for [com.payabli.sdk.core.network.PayabliRequest.headers].
     *
     * `X-Device-Id` is what `/config` reads the device from, where `/activate` takes it from the body. Sent on
     * both, because one assertion serves both.
     */
    fun asHeaders(): Map<String, String> =
        mapOf(
            HEADER_ASSERTION to assertion,
            HEADER_KEY_ID to keyId,
            HEADER_DEVICE_ID to deviceId,
            HEADER_TIMESTAMP to timestamp,
        )

    /** Never a value: every one of the four is either key material, device identity, or signed input. */
    override fun toString(): String = "DeviceAssertion()"

    private companion object {
        const val HEADER_ASSERTION = "X-App-Assertion"
        const val HEADER_KEY_ID = "X-App-KeyId"
        const val HEADER_DEVICE_ID = "X-Device-Id"
        const val HEADER_TIMESTAMP = "X-Assertion-Timestamp"

        /** The message names the field and never the value, since three of the four are secret or identity. */
        fun requireUsable(
            value: String,
            field: String,
        ) {
            require(value.isNotBlank()) { "$field must not be blank" }
            require(value.all { it in FIRST_PRINTABLE..LAST_PRINTABLE }) {
                "$field must be usable as an HTTP header value"
            }
            // A space is the printable floor, so the range check above accepts one at either end. HTTP treats
            // leading and trailing whitespace as optional padding around a field value rather than part of it,
            // so what is read back can legitimately be a trimmed string. For `timestamp` that is fatal and
            // silent: the signer signed the untrimmed value, verification runs over what arrived, and the
            // signature fails with nothing pointing at whitespace. The other three would mismatch a stored
            // identifier the same way. Rejected here, where the field is still named, rather than surfacing as
            // a verification failure two calls later.
            require(value.trim() == value) { "$field must not begin or end with whitespace" }
        }
    }
}
