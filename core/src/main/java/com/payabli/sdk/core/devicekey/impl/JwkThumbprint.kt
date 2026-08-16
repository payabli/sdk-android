package com.payabli.sdk.core.devicekey.impl

import java.security.MessageDigest
import kotlin.io.encoding.Base64

/**
 * The identifier a device key is known by on the wire: the JWK thumbprint of its public half, RFC 7638.
 *
 * Derived from the key instead of stored beside it, so there is no record that can be lost while the key it
 * names survives. It changes when the key changes, which is what the alias cannot do once the alias is fixed.
 *
 * The same value identifies the key in a device-bound credential's confirmation member, so deriving it here
 * gives one key one identifier rather than two.
 *
 * RFC 7638 Section 3 fixes every part of the input, and the output is only reproducible because it does. For
 * an EC key the required members are `crv`, `kty`, `x` and `y` (Section 3.2), ordered lexicographically with
 * no whitespace (Section 3.3), and the digest of that JSON is base64url-encoded (Section 3.4). The
 * coordinates carry no leading zero suppression: each is the curve's fixed 32 bytes, which is what
 * [EcPointEncoding] already produces.
 *
 * The string is built here rather than by a JSON encoder, which is free to reorder members or emit
 * whitespace. Either would change the digest while every test that round-trips the JSON still passed.
 */
internal object JwkThumbprint {
    private const val DIGEST = "SHA-256"

    /** RFC 7638 Section 3.4 specifies base64url, and Section 3.3's canonical form has no padding. */
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /**
     * @param uncompressedPoint the public point in X9.62 uncompressed form, as [EcPointEncoding] emits it.
     * @throws IllegalArgumentException if the point is not that shape.
     */
    fun of(uncompressedPoint: ByteArray): String {
        require(uncompressedPoint.size == EcPointEncoding.POINT_BYTES) {
            "the public point must be ${EcPointEncoding.POINT_BYTES} bytes"
        }
        // A compressed point carries one coordinate, so reading Y out of it would take bytes that are not
        // there and produce a thumbprint for a key that does not exist.
        require(uncompressedPoint[0] == EcPointEncoding.UNCOMPRESSED_TAG) {
            "the public point must be in uncompressed form"
        }

        val yStart = 1 + EcPointEncoding.COORDINATE_BYTES
        val x = base64Url.encode(uncompressedPoint, startIndex = 1, endIndex = yStart)
        val y = base64Url.encode(uncompressedPoint, startIndex = yStart, endIndex = EcPointEncoding.POINT_BYTES)

        val json = """{"crv":"P-256","kty":"EC","x":"$x","y":"$y"}"""
        return base64Url.encode(MessageDigest.getInstance(DIGEST).digest(json.toByteArray(Charsets.UTF_8)))
    }
}
