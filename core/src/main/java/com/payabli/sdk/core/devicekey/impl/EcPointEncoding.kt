package com.payabli.sdk.core.devicekey.impl

import java.math.BigInteger
import java.security.interfaces.ECPublicKey

/**
 * The device key's public point in X9.62 uncompressed form: `0x04 || X || Y`, 65 bytes for P-256.
 *
 * The service stores this and verifies every later assertion against it, so the length and the leading tag
 * are contractual rather than incidental. It rejects anything it cannot encode exactly rather than emitting
 * a shorter buffer: a truncated point is accepted at registration and then fails every signature check
 * afterwards, which surfaces as a broken device rather than as a bad key.
 *
 * `getEncoded()` is not this. That returns a `SubjectPublicKeyInfo` DER structure with the algorithm
 * identifier wrapped around the point, so it is longer and does not start with `0x04`.
 */
internal object EcPointEncoding {
    /** P-256, so each coordinate is a fixed 32 bytes whatever its numeric magnitude. */
    const val COORDINATE_BYTES: Int = 32

    const val POINT_BYTES: Int = 1 + 2 * COORDINATE_BYTES

    private const val UNCOMPRESSED_TAG: Byte = 0x04

    private const val FIELD_BITS = 256

    /**
     * @throws IllegalArgumentException if [key] is not on a 256-bit curve, or a coordinate does not fit.
     */
    fun uncompressed(key: ECPublicKey): ByteArray {
        val fieldSize = key.params.curve.field.fieldSize
        require(fieldSize == FIELD_BITS) {
            "the device key must be on a $FIELD_BITS-bit curve, got $fieldSize"
        }

        val point = key.w
        // The identity element has no affine coordinates and would encode as 65 zero bytes, which the
        // service would accept and never be able to verify against.
        require(point != java.security.spec.ECPoint.POINT_INFINITY) { "the device key's point is the identity" }

        return ByteArray(POINT_BYTES).also { out ->
            out[0] = UNCOMPRESSED_TAG
            coordinate(point.affineX, "X").copyInto(out, 1)
            coordinate(point.affineY, "Y").copyInto(out, 1 + COORDINATE_BYTES)
        }
    }

    /**
     * One coordinate as exactly [COORDINATE_BYTES], big-endian.
     *
     * `BigInteger.toByteArray` is two's-complement and variable length, so it gives 33 bytes when the high
     * bit is set (a leading zero for the sign) and fewer than 32 whenever the leading bytes are zero. Both
     * are ordinary for a valid coordinate, and copying the result in as-is would shift the value or overrun
     * the field.
     */
    private fun coordinate(
        value: BigInteger,
        name: String,
    ): ByteArray {
        require(value.signum() >= 0) { "coordinate $name is negative" }
        val bytes = value.toByteArray()

        return when {
            bytes.size == COORDINATE_BYTES -> bytes
            bytes.size == COORDINATE_BYTES + 1 && bytes[0] == 0.toByte() ->
                bytes.copyOfRange(1, bytes.size)
            bytes.size < COORDINATE_BYTES ->
                ByteArray(COORDINATE_BYTES).also { bytes.copyInto(it, COORDINATE_BYTES - bytes.size) }
            else -> throw IllegalArgumentException("coordinate $name does not fit in $COORDINATE_BYTES bytes")
        }
    }
}
