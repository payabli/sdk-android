package com.payabli.sdk.taptopay.attestation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Modifier

/** A valid nonce of exactly [length] characters, URL-safe base64 and nothing else. */
private fun nonceOf(length: Int): String = "a".repeat(length)

class AttestationChallengeTest {
    @Test
    fun `a standard challenge carries its value and its class`() {
        val challenge = AttestationChallenge.standard("c2hhLTI1Ni1kaWdlc3Q")

        assertEquals(VerdictClass.STANDARD, challenge.verdictClass)
        assertEquals("c2hhLTI1Ni1kaWdlc3Q", challenge.value)
    }

    @Test
    fun `a classic challenge carries its value and its class`() {
        val challenge = AttestationChallenge.classic(nonceOf(32))

        assertEquals(VerdictClass.CLASSIC, challenge.verdictClass)
        assertEquals(nonceOf(32), challenge.value)
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

    // --- nonce bounds -------------------------------------------------------------------------------

    @Test
    fun `a nonce of exactly the minimum length is accepted`() {
        assertEquals(16, AttestationChallenge.classic(nonceOf(16)).value.length)
    }

    @Test
    fun `a nonce one character short of the minimum is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AttestationChallenge.classic(nonceOf(15)) }
    }

    @Test
    fun `a nonce of exactly the maximum length is accepted`() {
        assertEquals(500, AttestationChallenge.classic(nonceOf(500)).value.length)
    }

    @Test
    fun `a nonce one character past the maximum is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AttestationChallenge.classic(nonceOf(501)) }
    }

    // --- nonce alphabet -----------------------------------------------------------------------------

    @Test
    fun `a nonce may use the URL-safe alphabet and may be padded`() {
        assertEquals("abcDEF012-_xyzAB", AttestationChallenge.classic("abcDEF012-_xyzAB").value)
        assertEquals("abcDEF012-_xyzA=", AttestationChallenge.classic("abcDEF012-_xyzA=").value)
        assertEquals("abcDEF012-_xyz==", AttestationChallenge.classic("abcDEF012-_xyz==").value)
    }

    @Test
    fun `a nonce in the standard alphabet is rejected`() {
        // '+' and '/' are exactly what URL-safe base64 replaces, and the platform rejects them.
        assertThrows(IllegalArgumentException::class.java) {
            AttestationChallenge.classic("abcDEF012+xyzABC")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AttestationChallenge.classic("abcDEF012/xyzABC")
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
            AttestationChallenge.classic("abcDEF0=12xyzABCD")
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
        val challenge = AttestationChallenge.classic("s3cr3tNonceValue")

        assertFalse(challenge.toString().contains("s3cr3tNonceValue"))
        assertEquals("AttestationChallenge(verdictClass=CLASSIC)", challenge.toString())
    }
}
