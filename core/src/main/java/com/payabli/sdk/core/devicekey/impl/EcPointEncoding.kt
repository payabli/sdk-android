package com.payabli.sdk.core.devicekey.impl

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint

/**
 * The device key's public point in X9.62 uncompressed form: `0x04 || X || Y`, 65 bytes for P-256.
 *
 * This is what every later assertion is verified against, so the length and the leading tag are contractual
 * rather than incidental. Anything that cannot be encoded exactly is rejected rather than emitted as a
 * shorter buffer: a truncated point survives registration and then fails every signature check afterwards,
 * which surfaces as a broken device rather than as a bad key.
 *
 * `getEncoded()` is not this. That returns a `SubjectPublicKeyInfo` DER structure with the algorithm
 * identifier wrapped around the point, so it is longer and does not start with `0x04`.
 */
internal object EcPointEncoding {
    /** P-256, so each coordinate is a fixed 32 bytes whatever its numeric magnitude. */
    const val COORDINATE_BYTES: Int = 32

    const val POINT_BYTES: Int = 1 + 2 * COORDINATE_BYTES

    /** Not private: [JwkThumbprint] reads the same tag, and two copies of a magic byte drift apart. */
    const val UNCOMPRESSED_TAG: Byte = 0x04

    private const val CURVE = "secp256r1"

    /** P-256's parameters as the platform defines them, which is what [isP256] compares against. */
    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec(CURVE))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    /**
     * @throws IllegalArgumentException if [key] is not a P-256 key, or a coordinate does not fit.
     */
    fun uncompressed(key: ECPublicKey): ByteArray {
        // This encoding carries no curve identifier, so it is unambiguous only if the curve is fixed. A
        // 256-bit curve with different coefficients has the same coordinate width and encodes to the same 65
        // bytes, and a point read back as P-256 can never verify a signature from the key it came from.
        require(isP256(key.params)) { "the device key must be a $CURVE key" }

        val point = key.w
        // The identity element has no affine coordinates and would encode as 65 zero bytes, which is a
        // well-formed point that no signature can ever verify against.
        require(point != ECPoint.POINT_INFINITY) { "the device key's point is the identity" }

        return ByteArray(POINT_BYTES).also { out ->
            out[0] = UNCOMPRESSED_TAG
            coordinate(point.affineX, "X").copyInto(out, 1)
            coordinate(point.affineY, "Y").copyInto(out, 1 + COORDINATE_BYTES)
        }
    }

    /**
     * Whether [spec] is P-256 in every respect that decides what a point means.
     *
     * `ECParameterSpec` declares no `equals`, so the members are compared one at a time. The curve carries
     * the field and both coefficients and does declare one.
     */
    private fun isP256(spec: ECParameterSpec): Boolean =
        spec.curve == p256.curve &&
            spec.generator == p256.generator &&
            spec.order == p256.order &&
            spec.cofactor == p256.cofactor

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
