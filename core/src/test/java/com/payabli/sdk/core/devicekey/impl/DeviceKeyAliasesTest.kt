package com.payabli.sdk.core.devicekey.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** The alias namespace: unique per key, and safe everywhere an alias travels. */
class DeviceKeyAliasesTest {
    @Test
    fun `a fresh alias sits under the versioned prefix`() {
        assertTrue(DeviceKeyAliases.newAlias().startsWith(DeviceKeyAliases.PREFIX + "."))
    }

    @Test
    fun `two aliases differ`() {
        // A constant alias would name every install's key the same thing, and a rotated key could not be
        // told from the key it replaced.
        assertNotEquals(DeviceKeyAliases.newAlias(), DeviceKeyAliases.newAlias())
    }

    @Test
    fun `the generated half is 128 bits of lowercase hex`() {
        val suffix = DeviceKeyAliases.newAlias().removePrefix(DeviceKeyAliases.PREFIX + ".")

        assertEquals(32, suffix.length)
        assertTrue("expected lowercase hex, got $suffix", suffix.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `a byte with the high bit set is two hex digits, not a sign`() {
        // "%02x".format on a negative Byte has produced eight-digit output before now, which would make the
        // alias longer than declared and its uniqueness argument wrong.
        val alias = DeviceKeyAliases.newAlias(AllOnes())

        assertEquals("f".repeat(32), alias.removePrefix(DeviceKeyAliases.PREFIX + "."))
    }

    @Test
    fun `an alias travels safely as a header value`() {
        // It is sent as one when an assertion carries it, and a character outside this range would be
        // rejected by the transport with an unrelated-looking failure.
        assertTrue(DeviceKeyAliases.newAlias().all { it in ' '..'~' })
    }

    @Test
    fun `only aliases from this namespace are recognised`() {
        assertTrue(DeviceKeyAliases.isDeviceKeyAlias(DeviceKeyAliases.newAlias()))
        assertFalse(DeviceKeyAliases.isDeviceKeyAlias("com.payabli.sdk.core.storage.v1.abcdef"))
        assertFalse("the bare prefix names no key", DeviceKeyAliases.isDeviceKeyAlias(DeviceKeyAliases.PREFIX))
        assertFalse(DeviceKeyAliases.isDeviceKeyAlias(""))
    }

    @Test
    fun `the prefix alone does not make a name one of ours`() {
        val head = DeviceKeyAliases.PREFIX + "."

        // A hand edit, a truncated write, or a later scheme sharing the prefix. Accepting any of these hands
        // back a name as one this minted, and whatever holds keys then looks up an alias it never created.
        assertFalse("a non-hex suffix", DeviceKeyAliases.isDeviceKeyAlias(head + "z".repeat(32)))
        assertFalse("a short suffix", DeviceKeyAliases.isDeviceKeyAlias(head + "ab"))
        assertFalse("a long suffix", DeviceKeyAliases.isDeviceKeyAlias(head + "a".repeat(33)))
        assertFalse("a suffix with a separator in it", DeviceKeyAliases.isDeviceKeyAlias(head + "a".repeat(29) + ".ab"))
    }

    @Test
    fun `an uppercase suffix is a different name, not the same one`() {
        // Key store aliases are compared verbatim, so accepting both spellings would treat two distinct
        // entries as one name.
        assertFalse(DeviceKeyAliases.isDeviceKeyAlias(DeviceKeyAliases.newAlias().uppercase()))
        assertFalse(DeviceKeyAliases.isDeviceKeyAlias(DeviceKeyAliases.PREFIX + "." + "A".repeat(32)))
    }
}

/** Fills every byte with 0xFF, so the hex formatting of a negative byte is asserted rather than hoped for. */
private class AllOnes : SecureRandom() {
    override fun nextBytes(bytes: ByteArray) {
        bytes.fill(0xFF.toByte())
    }
}
