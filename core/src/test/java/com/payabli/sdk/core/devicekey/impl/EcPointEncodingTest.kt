package com.payabli.sdk.core.devicekey.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint

/**
 * The 65-byte point the service stores and verifies every later assertion against.
 *
 * A real generated key covers the ordinary case; the crafted ones cover the coordinate widths a generated
 * key produces only by chance. `BigInteger.toByteArray` is two's-complement and variable length, so a
 * coordinate with its high bit set arrives 33 bytes long and one with leading zero bytes arrives short, and
 * both are ordinary values rather than errors. Waiting for a generator to produce them would make this test
 * pass or fail on which key it happened to mint.
 */
class EcPointEncodingTest {
    private val realKey: ECPublicKey =
        KeyPairGenerator.getInstance("EC").run {
            initialize(256)
            generateKeyPair().public as ECPublicKey
        }

    /** A P-256 parameter set, taken from a real key so the field size is the platform's own. */
    private val p256: ECParameterSpec = realKey.params

    private fun keyAt(
        x: BigInteger,
        y: BigInteger,
        params: ECParameterSpec = p256,
    ): ECPublicKey = CraftedEcPublicKey(ECPoint(x, y), params)

    @Test
    fun `a generated key encodes to a tagged 65-byte point`() {
        val encoded = EcPointEncoding.uncompressed(realKey)

        assertEquals(65, encoded.size)
        assertEquals("the uncompressed form must be tagged 0x04", 0x04.toByte(), encoded[0])
    }

    @Test
    fun `the encoded halves are the key's own coordinates`() {
        val encoded = EcPointEncoding.uncompressed(realKey)

        // Read back as unsigned, which is what the tag promises the two halves are.
        val x = BigInteger(1, encoded.copyOfRange(1, 33))
        val y = BigInteger(1, encoded.copyOfRange(33, 65))
        assertEquals(realKey.w.affineX, x)
        assertEquals(realKey.w.affineY, y)
    }

    @Test
    fun `a coordinate whose high bit is set loses the sign byte rather than the value`() {
        // toByteArray gives 33 bytes here: 0x00 then 32 bytes of value.
        val high = BigInteger(1, ByteArray(32) { 0xFF.toByte() })
        assertEquals("premise: this value needs a sign byte", 33, high.toByteArray().size)

        val encoded = EcPointEncoding.uncompressed(keyAt(high, BigInteger.TEN))

        assertEquals(65, encoded.size)
        assertEquals(high, BigInteger(1, encoded.copyOfRange(1, 33)))
    }

    @Test
    fun `a small coordinate is left-padded, not left-aligned`() {
        val encoded = EcPointEncoding.uncompressed(keyAt(BigInteger.ONE, BigInteger.ONE))

        // Left-aligned, the value would read as 2^248 rather than 1, and the signature would never verify.
        assertEquals(BigInteger.ONE, BigInteger(1, encoded.copyOfRange(1, 33)))
        assertEquals(BigInteger.ONE, BigInteger(1, encoded.copyOfRange(33, 65)))
        assertEquals(0x00.toByte(), encoded[1])
        assertEquals(0x01.toByte(), encoded[32])
    }

    @Test
    fun `a curve that is not 256 bits is refused`() {
        val p521 =
            KeyPairGenerator.getInstance("EC").run {
                initialize(521)
                (generateKeyPair().public as ECPublicKey).params
            }

        val thrown =
            runCatching { EcPointEncoding.uncompressed(keyAt(BigInteger.ONE, BigInteger.ONE, p521)) }
                .exceptionOrNull()

        // Encoding it anyway would truncate the coordinates into 32 bytes and produce a point that is not
        // the key, which the service would store and then fail to verify against forever.
        assertTrue("expected a refusal, got $thrown", thrown is IllegalArgumentException)
    }

    @Test
    fun `the identity element is refused`() {
        val thrown =
            runCatching { EcPointEncoding.uncompressed(CraftedEcPublicKey(ECPoint.POINT_INFINITY, p256)) }
                .exceptionOrNull()

        // It has no affine coordinates; reading them throws, and encoding zeros would be a point the
        // service accepts and can never verify against.
        assertTrue("expected a refusal, got $thrown", thrown is IllegalArgumentException)
    }

    @Test
    fun `a coordinate wider than the field is refused`() {
        val tooWide = BigInteger(1, ByteArray(33) { 0xFF.toByte() })

        val thrown = runCatching { EcPointEncoding.uncompressed(keyAt(tooWide, BigInteger.ONE)) }.exceptionOrNull()

        assertTrue("expected a refusal, got $thrown", thrown is IllegalArgumentException)
    }
}

/**
 * An `ECPublicKey` with coordinates chosen by the test.
 *
 * Hand-written because there is no mocking framework here, and because a `KeyFactory` validates that a point
 * is on the curve, which is exactly what these cases deliberately are not.
 */
private class CraftedEcPublicKey(
    private val point: ECPoint,
    private val parameters: ECParameterSpec,
) : ECPublicKey {
    override fun getW(): ECPoint = point

    override fun getParams(): ECParameterSpec = parameters

    override fun getAlgorithm(): String = "EC"

    override fun getFormat(): String? = null

    override fun getEncoded(): ByteArray? = null
}
