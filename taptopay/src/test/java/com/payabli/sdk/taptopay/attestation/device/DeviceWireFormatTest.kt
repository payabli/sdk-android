package com.payabli.sdk.taptopay.attestation.device

import com.payabli.sdk.core.network.PayabliEnvelope
import com.payabli.sdk.core.network.PayabliJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Values chosen to be recognisable in a rendered string, so a leak is visible rather than inferred. */
private const val SECRET_CODE = "654321"
private const val SECRET_ATTESTATION = "an-integrity-token-encoded"
private const val SECRET_PUBLIC_KEY = "a-public-key"
private const val SECRET_KEY_ID = "a-keystore-alias"
private const val SECRET_HARDWARE_ID = "a-hardware-id"
private const val SECRET_DEVICE_ID = "a-device-id"

class DeviceWireFormatTest {
    @Test
    fun `no request type prints anything it carries beyond the platform`() {
        val rendered =
            listOf(
                ChallengeRequest(entry = "an-entrypoint").toString(),
                RegisterRequest(
                    entry = "an-entrypoint",
                    hardwareId = SECRET_HARDWARE_ID,
                    keyId = SECRET_KEY_ID,
                    deviceName = "a-device-name",
                    model = "a-model",
                    osVersion = "an-os-version",
                    platform = DEVICE_PLATFORM,
                ).toString(),
                AttestRequest(
                    entry = "an-entrypoint",
                    challengeId = "a-challenge-id",
                    deviceId = SECRET_DEVICE_ID,
                    keyId = SECRET_KEY_ID,
                    appId = "com.partner.app",
                    attestation = SECRET_ATTESTATION,
                    publicKey = SECRET_PUBLIC_KEY,
                    platform = DEVICE_PLATFORM,
                ).toString(),
                ActivateRequest(
                    entry = "an-entrypoint",
                    deviceId = SECRET_DEVICE_ID,
                    activationCode = SECRET_CODE,
                ).toString(),
            ).joinToString(" ")

        // These are plain classes with hand-written renderings precisely so this assertion can exist: a data
        // class would print every field, and `toString` reaches exception messages the logger cannot redact.
        // The activation code is a live secret inside its window; the rest is device identity or key material.
        for (
        secret in
        listOf(
            SECRET_CODE,
            SECRET_ATTESTATION,
            SECRET_PUBLIC_KEY,
            SECRET_KEY_ID,
            SECRET_HARDWARE_ID,
            SECRET_DEVICE_ID,
        )
        ) {
            assertFalse(secret, rendered.contains(secret))
        }
        // The platform is the one value worth printing: it is a constant, and a request that named the wrong
        // one would otherwise be invisible in a diagnostic.
        assertTrue(rendered.contains("platform=Android"))
    }

    @Test
    fun `no response type prints an identifier either`() {
        val rendered =
            listOf(
                ChallengeResponse(challengeId = "a-challenge-id", challenge = "Y2hhbGxlbmdl").toString(),
                RegisterResponse(deviceId = SECRET_DEVICE_ID, status = STATUS_PENDING).toString(),
                AttestResponse(registered = true, isSandbox = false).toString(),
                ActivateResponse(deviceId = SECRET_DEVICE_ID, status = "active").toString(),
            ).joinToString(" ")

        assertFalse(rendered.contains(SECRET_DEVICE_ID))
        assertFalse(rendered.contains("a-challenge-id"))
        assertFalse(rendered.contains("Y2hhbGxlbmdl"))
        // The two derived facts that are safe and worth having: whether activation is still owed, and whether
        // the service treated the attestation as a sandbox one. Neither is an identifier.
        assertTrue(rendered.contains("isPending=true"))
        assertTrue(rendered.contains("isSandbox=false"))
    }

    @Test
    fun `an unknown key in the payload does not fail the decode`() {
        val body =
            """
            {"responseText":"Success","isSuccess":true,
             "responseData":{"deviceId":"$SECRET_DEVICE_ID","status":"pending","capabilities":{"nfc":true}}}
            """.trimIndent()

        val decoded =
            PayabliJson.format
                .decodeFromString(PayabliEnvelope.Success.serializer(RegisterResponse.serializer()), body)
                .responseData

        // The service adds response fields without notice, and `responseData` is typed `object` server-side,
        // so a decode that refused an unrecognised key would break on a field nobody asked for.
        assertEquals(SECRET_DEVICE_ID, decoded?.deviceId)
        assertTrue(decoded!!.isPending)
    }

    @Test
    fun `a nullable field decodes from an absent key and from an explicit null alike`() {
        val absent = """{"registered":true}"""
        val explicit = """{"registered":true,"isSandbox":null}"""

        val fromAbsent = PayabliJson.format.decodeFromString(AttestResponse.serializer(), absent)
        val fromExplicit = PayabliJson.format.decodeFromString(AttestResponse.serializer(), explicit)

        // `explicitNulls = false` covers the absent case for a nullable property with no default, which is why
        // none of these types declares `= null`. Both spellings reach the same value, so a service that starts
        // emitting nulls where it used to omit them changes nothing here.
        assertNull(fromAbsent.isSandbox)
        assertNull(fromExplicit.isSandbox)
        assertEquals(true, fromAbsent.registered)
    }

    @Test
    fun `a required field missing from the payload is a decode failure`() {
        val failure =
            runCatching {
                PayabliJson.format.decodeFromString(ChallengeResponse.serializer(), """{"challengeId":"c-1"}""")
            }.exceptionOrNull()

        // Non-nullable is the deliberate statement that this SDK cannot proceed without the field, and the
        // throw is what the client catches and reports as Undecodable. Nullability here is load-bearing.
        assertTrue(failure is Exception)
    }
}
