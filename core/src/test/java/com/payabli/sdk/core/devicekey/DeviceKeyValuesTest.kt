package com.payabli.sdk.core.devicekey

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

private const val IDENTITY = "oKIywvGUpTVTyxMQ3bwIIeQUudfr_CkLMjCE19ECD-U"

/**
 * What the two pairing types carry, and what they refuse to render.
 *
 * Both exist so a signature or a public key travels with the identifier of the key it belongs to, and both
 * are plain classes with a hand-written `toString` rather than data classes. That is the part worth a test:
 * a generated `toString` prints every field it holds, and these hold device identity, which reaches exception
 * messages and diagnostics that the logger cannot redact.
 */
class DeviceKeyValuesTest {
    private val point = ByteArray(65) { if (it == 0) 0x04 else it.toByte() }

    @Test
    fun `a public key carries the point and the identifier it was given`() {
        val subject = DevicePublicKey(point, IDENTITY)

        assertArrayEquals(point, subject.point)
        assertEquals(IDENTITY, subject.identity)
    }

    @Test
    fun `a public key renders neither the point nor the identifier`() {
        val rendered = DevicePublicKey(point, IDENTITY).toString()

        assertFalse("the identifier reached toString: $rendered", rendered.contains(IDENTITY))
        // A generated toString prints a ByteArray as its reference, which is not the point but is still a
        // field this type promises not to render.
        assertEquals("DevicePublicKey()", rendered)
    }

    @Test
    fun `a signature carries the bytes and the identifier it was given`() {
        val signature = byteArrayOf(0x30, 0x44, 0x02, 0x20)

        val subject = DeviceSignature(signature, IDENTITY)

        assertArrayEquals(signature, subject.signature)
        assertEquals(IDENTITY, subject.identity)
    }

    @Test
    fun `a signature renders neither the bytes nor the identifier`() {
        val rendered = DeviceSignature(byteArrayOf(0x30, 0x44), IDENTITY).toString()

        assertFalse("the identifier reached toString: $rendered", rendered.contains(IDENTITY))
        assertEquals("DeviceSignature()", rendered)
    }
}
