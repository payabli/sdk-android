package com.payabli.sdk.taptopay.attestation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AttestationTokenTest {
    @Test
    fun `the token is held verbatim`() {
        // Whitespace, an unexpected shape and an unusual length are all things a well-meaning helper might
        // "fix". The token is a document addressed to a verifier, and any repair is a forgery.
        val awkward = "  eyJhbGciOiJFUzI1NiJ9..not-a-jwt..\n\t "

        assertEquals(awkward, AttestationToken(awkward).value)
    }

    @Test
    fun `an empty token is not rejected here`() {
        // Not an endorsement: it says the type has no opinion. Whether an empty token means anything is a
        // verifier's question, and a client-side shape check is a decision an attacker gets to influence.
        assertEquals("", AttestationToken("").value)
    }

    @Test
    fun `toString does not contain the token`() {
        val token = AttestationToken("eyJhbGciOiJFUzI1NiJ9.payload.signature")

        assertFalse(token.toString().contains("eyJhbGciOiJFUzI1NiJ9"))
        assertFalse(token.toString().contains("signature"))
        assertEquals("AttestationToken(REDACTED)", token.toString())
    }

    @Test
    fun `interpolating a token into a string does not leak it`() {
        // The realistic leak is not `token.value` in a log call, which review catches. It is a token
        // interpolated into a message or a data class, where `toString()` is what gets written.
        val token = AttestationToken("eyJhbGciOiJFUzI1NiJ9.payload.signature")

        assertEquals("attestation completed: AttestationToken(REDACTED)", "attestation completed: $token")
    }
}
