package com.payabli.sdk.core.telemetry

import androidx.annotation.RestrictTo
import java.security.MessageDigest

/**
 * The digest a telemetry field carries in place of a value that names a merchant or an app.
 *
 * **SHA-256 of the value exactly as given, first 16 bytes, lowercase hex**, which is the shape
 * `DeviceIdentifier` already produces. Nothing is trimmed and nothing is case folded: the bytes hashed are
 * the ones the request is authorized with, so the hash and the plaintext cannot disagree, and the sibling
 * platform reproduces the value by hashing the same bytes. Changing any of that is a change to what both
 * platforms send, not a refactor.
 *
 * **Obfuscation rather than secrecy.** Entry points and package names are small enumerable sets, so anyone
 * holding these can build the table and reverse them. What it buys is that the raw value is absent from
 * everything reading event bodies, while the batch envelope still carries the entry point that authorizes
 * the request.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object TelemetryDigest {
    /** Half a SHA-256, so the result is 32 lowercase hex characters. */
    internal const val DIGEST_BYTES: Int = 16

    /** [value] as a digest, or a blank for a blank, which the wire omits rather than sending empty. */
    public fun of(value: String): String {
        if (value.isBlank()) return ""

        return MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .toHexString(0, DIGEST_BYTES)
    }
}
