package com.payabli.sdk.core.storage.impl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversions that keep plaintext out of `String`, where a secret could not be overwritten.
 *
 * Correctness first, since a subtly wrong encoder would corrupt every stored value, then the wiping
 * behaviour that is the reason these exist at all.
 */
class SecretBuffersTest {
    @Test
    fun `text round-trips through bytes`() {
        val original = "refresh-secret-value".toCharArray()
        val bytes = SecretBuffers.toBytes(original)

        assertArrayEquals(original, SecretBuffers.toChars(bytes))
    }

    /** Multi-byte input, because an encoder that assumed one byte per char would pass the ASCII test. */
    @Test
    fun `multi-byte characters survive the round trip`() {
        val original = "sécret-é中文".toCharArray()
        val bytes = SecretBuffers.toBytes(original)

        assertEquals("UTF-8 should be wider than the character count", true, bytes.size > original.size)
        assertArrayEquals(original, SecretBuffers.toChars(bytes))
    }

    /** The caller owns what it passes in, so the conversion must not clear it as a side effect. */
    @Test
    fun `converting does not disturb the caller's array`() {
        val original = "secret".toCharArray()
        SecretBuffers.toBytes(original)

        assertArrayEquals("secret".toCharArray(), original)
    }

    @Test
    fun `wipe overwrites bytes and chars`() {
        val bytes = byteArrayOf(1, 2, 3)
        val chars = "abc".toCharArray()

        SecretBuffers.wipe(bytes)
        SecretBuffers.wipe(chars)

        assertArrayEquals(ByteArray(3), bytes)
        assertTrue("characters were not overwritten", chars.all { it == '\u0000' })
    }

    @Test
    fun `wiping null is a no-op rather than a crash`() {
        SecretBuffers.wipe(null as ByteArray?)
        SecretBuffers.wipe(null as CharArray?)
    }
}
