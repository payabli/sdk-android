package com.payabli.sdk.taptopay.attestation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.Base64

/**
 * A nonce carrying exactly [bytes] decoded bytes, which is the axis the platform's floor is on.
 *
 * Built by encoding rather than by repeating a character, because the two are not the same measurement:
 * sixteen characters of base64 carry twelve bytes, so a hand-written sixteen-character string looks like a
 * minimum-length nonce and is not one.
 */
private fun nonceOfBytes(bytes: Int): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes) { 'a'.code.toByte() })

/** How many bytes a nonce actually carries, which is what the platform's floor is measured against. */
private fun decodedSize(nonce: String): Int = Base64.getUrlDecoder().decode(nonce).size

class AttestationChallengeTest {
    @Test
    fun `a standard challenge carries its value and its class`() {
        val challenge = AttestationChallenge.standard("c2hhLTI1Ni1kaWdlc3Q")

        assertEquals(VerdictClass.STANDARD, challenge.verdictClass)
        assertEquals("c2hhLTI1Ni1kaWdlc3Q", challenge.value)
    }

    @Test
    fun `a classic challenge carries its value and its class`() {
        val challenge = AttestationChallenge.classic(nonceOfBytes(32))

        assertEquals(VerdictClass.CLASSIC, challenge.verdictClass)
        assertEquals(nonceOfBytes(32), challenge.value)
    }

    // --- requestHash bounds -------------------------------------------------------------------------

    @Test
    fun `a request hash of exactly the maximum length is accepted`() {
        assertEquals(500, AttestationChallenge.standard("a".repeat(500)).value.length)
    }

    @Test
    fun `a request hash one character past the maximum is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AttestationChallenge.standard("a".repeat(501))
        }
    }

    @Test
    fun `an empty request hash is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AttestationChallenge.standard("") }
    }

    @Test
    fun `a request hash within the character limit but over it in UTF-8 bytes is rejected`() {
        // 250 characters, three bytes each once encoded: inside the character limit, outside the byte one.
        // The two limits are not the same limit, and a check on length alone would pass this.
        assertThrows(IllegalArgumentException::class.java) {
            AttestationChallenge.standard("中".repeat(250))
        }
    }

    @Test
    fun `a request hash has no alphabet rule`() {
        // Not base64, deliberately: the platform treats a requestHash as opaque, so neither do we.
        assertEquals("a+b/c=~!", AttestationChallenge.standard("a+b/c=~!").value)
    }

    // --- nonce bounds, measured in decoded bytes -----------------------------------------------------

    @Test
    fun `a nonce carrying exactly the minimum bytes is accepted`() {
        assertEquals(16, decodedSize(AttestationChallenge.classic(nonceOfBytes(16)).value))
    }

    @Test
    fun `a nonce one byte short of the minimum is rejected`() {
        // 15 bytes encode to 20 characters, comfortably past the guide's 16-character floor, so a check
        // written against the text accepts this and the platform answers NONCE_TOO_SHORT.
        assertEquals(20, nonceOfBytes(15).length)
        assertThrows(IllegalArgumentException::class.java) { AttestationChallenge.classic(nonceOfBytes(15)) }
    }

    @Test
    fun `a sixteen-character nonce is rejected, because it carries only twelve bytes`() {
        // The case that makes the two measurements visibly different, and the one an earlier revision of
        // this file asserted was the valid minimum.
        assertEquals(12, decodedSize("aaaaaaaaaaaaaaaa"))
        assertThrows(IllegalArgumentException::class.java) { AttestationChallenge.classic("aaaaaaaaaaaaaaaa") }
    }

    @Test
    fun `a nonce of exactly the maximum length is accepted`() {
        // 375 bytes is the most that fits in the character ceiling, and encodes to exactly 500.
        val longest = nonceOfBytes(375)
        assertEquals(500, longest.length)
        assertEquals(500, AttestationChallenge.classic(longest).value.length)
    }

    @Test
    fun `a nonce past the maximum length is rejected`() {
        // One byte more crosses the ceiling; base64 grows in steps, so 376 bytes is 502 characters and
        // there is no encodable value of exactly 501.
        assertEquals(502, nonceOfBytes(376).length)
        assertThrows(IllegalArgumentException::class.java) { AttestationChallenge.classic(nonceOfBytes(376)) }
    }

    @Test
    fun `a value in the alphabet that is not decodable base64 is rejected`() {
        // A trailing group of one character encodes no whole byte, so this is not a valid base64 length.
        //
        // The length is chosen so that only the decode can reject it. It must sit inside the character
        // ceiling, or the ceiling rejects it first; and it must be long enough that a naive byte estimate
        // clears the floor, or the floor does. 25 characters is roughly 18 bytes, so both other checks
        // would pass it. Measured: at 21 characters this test passed with the decode removed.
        val undecodable = "a".repeat(25)
        assertEquals(1, undecodable.length % 4)
        assertTrue(undecodable.length <= 500)
        assertTrue(undecodable.length * 3 / 4 >= 16)
        assertThrows(IllegalArgumentException::class.java) { AttestationChallenge.classic(undecodable) }
    }

    // --- nonce alphabet -----------------------------------------------------------------------------

    @Test
    fun `a nonce may use the URL-safe alphabet and may be padded`() {
        // 24 characters so the value clears the byte floor; the point here is the alphabet and the padding.
        assertEquals("abcDEF012-_xyzABabcDEF01", AttestationChallenge.classic("abcDEF012-_xyzABabcDEF01").value)
        assertEquals("abcDEF012-_xyzABabcDEF==", AttestationChallenge.classic("abcDEF012-_xyzABabcDEF==").value)
    }

    @Test
    fun `a nonce in the standard alphabet is rejected`() {
        // '+' and '/' are exactly what URL-safe base64 replaces, and the platform rejects them.
        assertThrows(IllegalArgumentException::class.java) {
            AttestationChallenge.classic("abcDEF012+xyzABabcDEF01")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttestationChallenge.classic("abcDEF012/xyzABabcDEF01")
        }
    }

    @Test
    fun `a wrapped nonce is rejected`() {
        // The whole content of "non-wrapping": an encoder left at its default line length produces this.
        assertThrows(IllegalArgumentException::class.java) {
            AttestationChallenge.classic("abcDEF012xyzABCD\nabcDEF012xyzABCD")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttestationChallenge.classic("abcDEF012xyzABCD\r\nabcDEF012xyzABCD")
        }
    }

    @Test
    fun `a nonce padded anywhere but the end is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AttestationChallenge.classic("abcDEF0=12xyzABabcDEF01")
        }
    }

    // --- server-issued ------------------------------------------------------------------------------

    @Test
    fun `a challenge can only be built from a value the caller was given`() {
        // Freshness a client mints is not freshness, so the surface has to make self-issuing impossible
        // rather than discouraged. Two things enforce it: the constructor is not reachable, and every
        // way in takes the value. Adding `AttestationChallenge.random()` fails this test, and the review
        // that adds it then has to say what a self-issued challenge would prove.
        // Synthetic ones are excluded: a private Kotlin constructor reached from a companion gets a
        // compiler-generated public accessor taking a DefaultConstructorMarker, which no caller can name.
        val constructors =
            AttestationChallenge::class.java.declaredConstructors.filterNot { it.isSynthetic }
        assertFalse(
            "AttestationChallenge must have no public constructor",
            constructors.any { Modifier.isPublic(it.modifiers) },
        )

        val entryPoints =
            AttestationChallenge.Companion::class
                .java
                .declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .associate { it.name to it.parameterTypes.toList() }

        assertEquals(
            mapOf(
                "standard" to listOf(String::class.java),
                "classic" to listOf(String::class.java),
            ),
            entryPoints,
        )
    }

    // --- disclosure ---------------------------------------------------------------------------------

    @Test
    fun `toString does not contain the value`() {
        val challenge = AttestationChallenge.classic("s3cr3tNonceValueLongEnough")

        assertFalse(challenge.toString().contains("s3cr3tNonceValueLongEnough"))
        assertEquals("AttestationChallenge(verdictClass=CLASSIC)", challenge.toString())
    }
}
