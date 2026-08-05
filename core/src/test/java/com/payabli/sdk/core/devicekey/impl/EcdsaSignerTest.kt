package com.payabli.sdk.core.devicekey.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature

private const val DER_SEQUENCE: Byte = 0x30
private const val DER_INTEGER: Byte = 0x02

/** 32 for the curve, plus the leading zero DER adds when the top bit would read as a sign. */
private const val MAX_INTEGER_BYTES = 33

/**
 * Signing, against a key generated on this JVM.
 *
 * The key store is what a device provides; the signing is platform code that behaves the same wherever the
 * key came from, so this is provable here rather than only on a device.
 */
class EcdsaSignerTest {
    private val keyPair: KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(256)
            generateKeyPair()
        }

    private val payload = "the-signed-bytes".toByteArray()

    private fun verifies(
        signature: ByteArray,
        over: ByteArray,
    ): Boolean =
        Signature.getInstance(EcdsaSigner.ALGORITHM).run {
            initVerify(keyPair.public)
            update(over)
            verify(signature)
        }

    @Test
    fun `the signature verifies against the matching public key`() {
        val signature = EcdsaSigner.sign(keyPair.private, payload)

        assertTrue("the signature did not verify over the payload it was made from", verifies(signature, payload))
    }

    @Test
    fun `the signature is a DER sequence of two integers, not a raw coordinate pair`() {
        val signature = EcdsaSigner.sign(keyPair.private, payload)

        // The framing itself, not its size. The verifier expects DER, and a raw R||S pair is the same two
        // numbers with no framing at all: it is rejected as malformed rather than as a wrong signature. Size
        // is the wrong way to tell them apart, because DER integers are minimally encoded, so a signature
        // whose r and s happen to carry leading zero bytes is shorter than the usual seventy-odd.
        val (r, s) = derIntegers(signature)

        assertTrue("r is wider than the curve allows", r.size <= MAX_INTEGER_BYTES)
        assertTrue("s is wider than the curve allows", s.size <= MAX_INTEGER_BYTES)
    }

    /**
     * The `r` and `s` of a DER `SEQUENCE { INTEGER, INTEGER }`, failing if the framing is anything else.
     *
     * Short-form lengths throughout: a P-256 signature is well under the 128 bytes that would need the long
     * form, so a length byte here is the length.
     */
    private fun derIntegers(signature: ByteArray): Pair<ByteArray, ByteArray> {
        assertEquals("expected a DER sequence tag", DER_SEQUENCE, signature[0])
        assertEquals(
            "the sequence length must cover exactly the rest of the buffer",
            signature.size - 2,
            signature[1].toInt(),
        )

        var offset = 2
        val values =
            List(2) {
                assertEquals("expected a DER integer tag", DER_INTEGER, signature[offset])
                val length = signature[offset + 1].toInt()
                assertTrue("a DER integer carries at least one content byte", length > 0)
                val end = offset + 2 + length
                assertTrue("the integer runs past the end of the buffer", end <= signature.size)
                signature.copyOfRange(offset + 2, end).also { offset = end }
            }

        assertEquals("the two integers must consume the whole sequence", signature.size, offset)
        return values[0] to values[1]
    }

    @Test
    fun `a signature does not verify over different bytes`() {
        val signature = EcdsaSigner.sign(keyPair.private, payload)

        // The one property the whole assertion rests on: the server re-derives the signed bytes from the
        // timestamp it was sent, so a signature over anything else has to fail.
        assertFalse("a signature verified over bytes it was not made from", verifies(signature, "other".toByteArray()))
    }

    @Test
    fun `signing twice produces two usable signatures`() {
        val first = EcdsaSigner.sign(keyPair.private, payload)
        val second = EcdsaSigner.sign(keyPair.private, payload)

        // ECDSA is randomised, so the two differ; both must still verify. An assertion is minted per call,
        // so this is the ordinary path rather than an edge case.
        assertTrue(verifies(first, payload))
        assertTrue(verifies(second, payload))
    }
}
