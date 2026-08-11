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
    fun `useDigits wipes the copy it lent, including when the block throws`() {
        // Retaining the argument is the only way to observe what the block was given, and nothing in production
        // retains it. Measured: with the wipe removed, all 274 tests in this module still passed, so until this
        // test existed the guarantee rested on reading the code.
        val value = SensitiveDigits.ofString(pan)
        var lent: CharArray? = null

        val outcome =
            runCatching {
                value.useDigits { digits ->
                    lent = digits
                    throw IllegalStateException("sentinel")
                }
            }

        assertEquals("sentinel", outcome.exceptionOrNull()?.message)
        assertTrue("the lent copy still holds digits", lent!!.all { it == SensitiveDigits.WIPED })
    }

    @Test
    fun `useDigits wipes the copy it lent on an ordinary return too`() {
        val value = SensitiveDigits.ofString(pan)
        var lent: CharArray? = null

        val length =
            value.useDigits { digits ->
                lent = digits
                digits.size
            }

        assertEquals(16, length)
        assertTrue("the lent copy still holds digits", lent!!.all { it == SensitiveDigits.WIPED })
        // The value itself is untouched: lending a copy is not closing the original.
        assertEquals(pan, String(value.read()))
    }

    @Test
    fun `no member that reaches the digits is callable from Java`() {
        // internal is a Kotlin rule only: these are emitted as public final read$payin() and friends, so
        // without @JvmSynthetic a Java caller in another module takes a copy of a card number. javac refuses
        // to resolve a synthetic method, which is what makes the annotation the enforcement.
        val reaching =
            SensitiveDigits::class.java.declaredMethods.filter {
                it.name.substringBefore('$') in setOf("read", "useDigits", "rawCopy", "isWiped")
            }

        assertEquals(4, reaching.size)
        reaching.forEach { assertTrue("${it.name} is reachable from Java", it.isSynthetic) }
    }

    @Test
    fun `an empty value is allowed here and refused by validation`() {
        // Constructing one is not the place to reject it: a caller passing a blank field deserves a typed
        // validation failure naming the field, not an exception from a constructor.
        val value = SensitiveDigits.ofString("")
        assertEquals(0, value.length)
    }
}
