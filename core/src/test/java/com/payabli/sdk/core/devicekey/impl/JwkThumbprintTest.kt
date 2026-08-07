package com.payabli.sdk.core.devicekey.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derivation the service records a key by, checked against an answer computed outside this code.
 *
 * A test that rebuilds the canonical JSON to predict the digest would agree with any member order and any
 * padding this file happened to use, so the expected value below was produced by a separate implementation
 * from the same public coordinates. Getting the order or the padding wrong changes it.
 */
class JwkThumbprintTest {
    private companion object {
        /**
         * A P-256 public key, verified to satisfy the curve equation rather than assumed to be one: a point
         * that is not on the curve is not a key any of this describes.
         */
        const val POINT_HEX =
            "04" +
                "7fcdce2770f6c45d4183cbee6fdb4b7b580733357be9ef13bacf6e3c7bd15445" +
                "c7f144cd1bbd9b7e872cdfedb9eeb9f4b3695d6ea90b24ad8a4623288588e5ad"

        const val EXPECTED = "oKIywvGUpTVTyxMQ3bwIIeQUudfr_CkLMjCE19ECD-U"

        /** SHA-256 is 32 bytes, which is 43 base64 characters once the padding is dropped. */
        const val DIGEST_CHARS = 43
    }

    private fun point(hex: String = POINT_HEX): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun theThumbprintMatchesTheValueComputedOutsideThisImplementation() {
        assertEquals(EXPECTED, JwkThumbprint.of(point()))
    }

    @Test
    fun theThumbprintIsBase64UrlWithoutPadding() {
        val thumbprint = JwkThumbprint.of(point())

        // The two ways this silently goes wrong: standard base64, whose `+` and `/` are not safe in a URL or
        // an HTTP header, and retained padding, which the canonical form does not carry. Both still produce a
        // stable string, so only the encoding itself catches them.
        assertEquals(DIGEST_CHARS, thumbprint.length)
        assertTrue(
            "the thumbprint is not base64url without padding: $thumbprint",
            thumbprint.all { it.isLetterOrDigit() || it == '-' || it == '_' },
        )
    }

    @Test
    fun oneChangedCoordinateBitChangesTheThumbprint() {
        val other = point().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

        // The identifier has to distinguish a key from the one it replaced, and the alias no longer can.
        assertNotEquals(JwkThumbprint.of(point()), JwkThumbprint.of(other))
    }

    @Test
    fun aPointOfTheWrongLengthIsRefused() {
        val thrown = runCatching { JwkThumbprint.of(point().copyOf(64)) }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $thrown", thrown is IllegalArgumentException)
    }

    @Test
    fun aCompressedPointIsRefused() {
        // 0x02 and 0x03 tag a compressed point, which carries X and a sign bit instead of Y. Reading Y out of
        // one takes bytes that belong to something else and yields a thumbprint for no key at all.
        val compressed = point().also { it[0] = 0x02 }

        val thrown = runCatching { JwkThumbprint.of(compressed) }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $thrown", thrown is IllegalArgumentException)
    }
}
