package com.payabli.sdk.core.devicekey.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature

private const val DER_SEQUENCE: Byte = 0x30

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
    fun `the signature is a DER sequence, not a raw coordinate pair`() {
        val signature = EcdsaSigner.sign(keyPair.private, payload)

        // The verifier expects DER. A raw R||S pair is the same two numbers and is not interchangeable: it
        // would be 64 bytes with no tag, and would be rejected as malformed rather than as a wrong signature.
        assertEquals("expected a DER sequence tag", DER_SEQUENCE, signature[0])
        assertTrue("a DER signature carries length bytes beyond the pair", signature.size > 64)
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
