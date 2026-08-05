package com.payabli.sdk.taptopay.attestation.device

private const val FIRST_PRINTABLE = ' '
private const val LAST_PRINTABLE = '~'

/**
 * The proof-of-possession headers `/activate` requires, and `/config` after it.
 *
 * The server re-derives what was signed from [timestamp] alone: `clientDataHash = SHA256(UTF8(timestamp))`,
 * and the signature is checked over that against the public key stored at attestation. **So [timestamp] must
 * be the exact string the signer signed, byte for byte.** That is why it is carried as a string rather than
 * an instant formatted here: a second formatting of the same moment can differ in fractional digits or offset
 * spelling, and the failure would surface as a signature mismatch with nothing pointing at the cause. The
 * shape the server parses is ISO-8601 with fractional seconds, and it accepts a window of 120 seconds plus 5
 * of skew, so an assertion is minted per call and never cached.
 *
 * This type only carries the values; producing them needs a Keystore EC key that nothing in this phase owns
 * yet (PLA-2182 consumes an assertion, PLA-2183's prerequisite produces one).
 *
 * Values are checked for header safety at construction. `HttpURLConnection.setRequestProperty` rejects an
 * illegal character with an unchecked `IllegalArgumentException` that escapes before the transport can map
 * it, so a caller would see the wrong exception type for the wrong reason; a carriage return or line feed
 * would additionally be header injection. `:core` makes the same check where a bearer token enters, but its
 * helper is `internal` to that module, so this is the same rule stated again rather than a second rule.
 */
internal class DeviceAssertion(
    /** Base64 of the DER ECDSA signature over `SHA256(UTF8(timestamp))`. */
    val assertion: String,
    /** The signing key's Keystore alias, matched against the attestation row. */
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
     * `X-Device-Id` is not read by `/activate`, which takes the device from the body, and is sent anyway
     * because `/config` does require it and one assertion serves both.
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
        }
    }
}
