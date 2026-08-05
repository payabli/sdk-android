package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.taptopay.attestation.AttestationChallenge
import com.payabli.sdk.taptopay.attestation.AttestationToken
import com.payabli.sdk.taptopay.attestation.VerdictClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Base64 of `server-issued-challenge-material`, standing in for a `challenge` from the service.
 *
 * Chosen rather than random for one property: its SHA-256 digest contains both a `+` and a `/` in the standard
 * alphabet, so a standard-alphabet encode of the digest is visibly different from a URL-safe one. A vector
 * without those characters would pass whichever encoder was used and prove nothing.
 */
private const val SERVER_CHALLENGE = "c2VydmVyLWlzc3VlZC1jaGFsbGVuZ2UtbWF0ZXJpYWw="

/** `base64url_nopad(SHA256(base64decode(SERVER_CHALLENGE)))`, computed independently of this SDK. */
private const val EXPECTED_NONCE = "AXAB8nFwa-5OKelil5Kuxwa_q1Ed2haU4FWpfTi5NX4"

class DeviceAttestationBindingTest {
    @Test
    fun `the nonce is the url-safe unpadded digest of the decoded challenge`() {
        val challenge = DeviceAttestationBinding.nonceChallenge(SERVER_CHALLENGE)

        // One assertion, three properties: decode the challenge before hashing, hash with SHA-256, and write
        // the digest URL-safe and unpadded. Any one of the three done differently changes this string, and
        // every one of them fails identically on the wire as "Play Integrity verification failed".
        assertEquals(EXPECTED_NONCE, challenge.value)
    }

    @Test
    fun `the nonce is not the standard alphabet and carries no padding`() {
        val challenge = DeviceAttestationBinding.nonceChallenge(SERVER_CHALLENGE)

        // Spelled out separately from the vector above so a future edit that changes the vector cannot quietly
        // change what the vector was chosen to demonstrate.
        assertEquals("AXAB8nFwa+5OKelil5Kuxwa/q1Ed2haU4FWpfTi5NX4=", standardBase64Digest())
        assertNotEquals(standardBase64Digest(), challenge.value)
        assertTrue(challenge.value.none { it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun `the derived challenge is classic and within the platform's shape rules`() {
        val challenge = DeviceAttestationBinding.nonceChallenge(SERVER_CHALLENGE)

        // Classic, because the current service verifies only the nonce shape. A standard challenge would bind
        // freshness to a requestHash nothing on the server reads.
        assertEquals(VerdictClass.CLASSIC, challenge.verdictClass)
        // The derivation always yields 43 characters over 32 bytes, so the classic factory's floor and ceiling
        // are satisfied by construction. Re-running it through that factory is what proves the claim rather
        // than restating it.
        assertEquals(EXPECTED_NONCE, AttestationChallenge.classic(challenge.value).value)
    }

    @Test
    fun `a challenge the live service issued derives the nonce the server expects`() {
        // Captured from api-qa's POST /challenge and long since expired: a challenge is single-use with a
        // five-minute life, so this value grants nothing. It is here because a recorded one pins two properties
        // the synthetic vector above cannot.
        //
        // The challenge itself contains `+`, which the URL-safe decoder rejects outright — so this fails if the
        // inbound decode is ever switched to `getUrlDecoder`. And its digest contains both `-` and `_`, so it
        // fails if the outbound encode is ever switched to the standard alphabet. Two encoders, opposite
        // alphabets, one direction each; getting either backwards is the whole failure mode.
        val issued = "LcFukiNU9kZ+t6RgegeroNSkA+w2atSqPYv1GYkb0G0="

        val challenge = DeviceAttestationBinding.nonceChallenge(issued)

        assertEquals("pHRn6ZW3EFfREl4-vnrR_X60s9sXa4QG7A17i9paQZ8", challenge.value)
        assertTrue(issued.contains('+'))
        assertTrue(challenge.value.contains('-') && challenge.value.contains('_'))
    }

    @Test
    fun `a challenge that is not base64 is reported as an undecodable response`() {
        val failure =
            runCatching { DeviceAttestationBinding.nonceChallenge("not%%base64") }.exceptionOrNull()

        // The service's fault, not the caller's: nothing a caller passes reaches this argument. So it is an
        // unusable response rather than an IllegalArgumentException blaming whoever made the call.
        assertTrue(failure is DeviceServiceException.Undecodable)
    }

    @Test
    fun `the attestation field is the token base64-encoded on top of its own encoding`() {
        val field = DeviceAttestationBinding.attestationField(AttestationToken("header.payload.signature"))

        // The token is already a compact JWS and the field wraps it again: the server decodes the field with a
        // standard-alphabet decode and reads the bytes as UTF-8 text. Sending the token unwrapped satisfies
        // the field's own validation and fails verification later, which is the failure this pins.
        assertEquals("aGVhZGVyLnBheWxvYWQuc2lnbmF0dXJl", field)
    }

    @Test
    fun `an empty token still encodes rather than being dropped`() {
        // Not a shape the platform produces, and asserted because the alternative to encoding it is emitting
        // an empty field the server would refuse with a message about base64 rather than about the token.
        assertEquals("", DeviceAttestationBinding.attestationField(AttestationToken("")))
    }

    private fun standardBase64Digest(): String =
        java.util.Base64
            .getEncoder()
            .encodeToString(
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                        java.util.Base64
                            .getDecoder()
                            .decode(SERVER_CHALLENGE),
                    ),
            )
}
