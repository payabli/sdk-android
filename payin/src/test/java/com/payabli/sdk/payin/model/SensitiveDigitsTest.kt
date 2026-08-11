package com.payabli.sdk.payin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three promises: nothing leaks through a message, a close overwrites, and a copy is not an alias. */
class SensitiveDigitsTest {
    private val pan = "4111111111111111"

    @Test
    fun `closing overwrites every character`() {
        val value = SensitiveDigits.ofString(pan)

        value.close()

        // read answers empty once wiped, so it cannot show whether the overwrite happened.
        assertTrue(value.rawCopy().all { it == SensitiveDigits.WIPED })
        assertTrue(value.isWiped)
    }

    @Test
    fun `use closes the value`() {
        val value = SensitiveDigits.ofString(pan)

        value.use { assertFalse(it.isWiped) }

        assertTrue(value.isWiped)
    }

    @Test
    fun `closing twice is not an error`() {
        val value = SensitiveDigits.ofString(pan)
        value.close()
        value.close()
        assertTrue(value.isWiped)
    }

    @Test
    fun `a closed value reads as empty rather than throwing`() {
        // A request built from a closed value has to fail validation as a missing field, not blow up inside
        // a body writer.
        val value = SensitiveDigits.ofString(pan)
        value.close()
        assertEquals(0, value.length)
        assertEquals(0, value.read().size)
    }

    @Test
    fun `the source array is copied, so the caller can wipe its own`() {
        val source = pan.toCharArray()
        val value = SensitiveDigits.of(source)

        source.fill(SensitiveDigits.WIPED)

        assertEquals(pan.length, value.length)
        assertEquals(pan, String(value.read()))
    }

    @Test
    fun `wiping the value leaves the caller's array alone`() {
        val source = pan.toCharArray()
        val value = SensitiveDigits.of(source)

        value.close()

        assertEquals(pan, String(source))
    }

    @Test
    fun `read hands out a copy rather than the buffer`() {
        val value = SensitiveDigits.ofString(pan)

        value.read().fill('7')

        assertEquals(pan, String(value.read()))
    }

    @Test
    fun `toString carries the length and no digits`() {
        val value = SensitiveDigits.ofString(pan)
        assertEquals("SensitiveDigits(length=16)", value.toString())
        assertFalse(value.toString().contains("4111"))
    }

    @Test
    fun `an empty value is allowed here and refused by validation`() {
        // Constructing one is not the place to reject it: a caller passing a blank field deserves a typed
        // validation failure naming the field, not an exception from a constructor.
        val value = SensitiveDigits.ofString("")
        assertEquals(0, value.length)
    }
}
