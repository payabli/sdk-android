package com.payabli.sdk.core.network

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayabliEnvelopeTest {
    @Serializable
    private class DeviceConfig(
        val entryPoint: String,
    )

    @Test
    fun `isSuccess false yields a decline carrying the result code and text`() {
        val outcome =
            PayabliEnvelope.declineOutcome(
                """{"isSuccess":false,"responseData":{"resultCode":51,"resultText":"do not honor"}}""",
            )

        assertNotNull(outcome)
        assertEquals(51, outcome?.code)
        assertEquals("do not honor", outcome?.reason)
    }

    @Test
    fun `isSuccess true is not a decline`() {
        val json = """{"isSuccess":true,"responseData":{"entryPoint":"abc"}}"""
        assertNull(PayabliEnvelope.declineOutcome(json))
    }

    @Test
    fun `reason falls back to top-level responseText when the decline payload has none`() {
        val outcome = PayabliEnvelope.declineOutcome("""{"isSuccess":false,"responseText":"outer reason"}""")
        assertEquals("outer reason", outcome?.reason)
        assertNull(outcome?.code)
    }

    @Test
    fun `reason falls back to a default when the body carries no text at all`() {
        val outcome = PayabliEnvelope.declineOutcome("""{"isSuccess":false}""")
        assertEquals("server declined", outcome?.reason)
    }

    @Test
    fun `a malformed body reads as not a decline rather than throwing`() {
        // Every legacy field is optional, so the Success decode is what surfaces the error.
        assertNull(PayabliEnvelope.declineOutcome("not json at all"))
        assertNull(PayabliEnvelope.declineOutcome(""))
        assertNull(PayabliEnvelope.declineOutcome("{"))
    }

    @Test
    fun `the byte array overload tolerates a malformed body too`() {
        assertNull(PayabliEnvelope.declineOutcome("not json at all".toByteArray(Charsets.UTF_8)))
        assertNull(PayabliEnvelope.declineOutcome(ByteArray(0)))
    }

    @Test
    fun `a decline whose payload does not decode still yields an outcome with no code`() {
        // responseData is a string where DeclineEnvelope wants an object, so the Status decode succeeds
        // and the payload decode does not. That stays a decline, and the reason falls back.
        val outcome =
            PayabliEnvelope.declineOutcome(
                """{"isSuccess":false,"responseText":"outer reason","responseData":"not an object"}""",
            )

        assertNotNull(outcome)
        assertNull(outcome?.code)
        assertEquals("outer reason", outcome?.reason)
    }

    @Test
    fun `an absent isSuccess reads as not a decline`() {
        assertNull(PayabliEnvelope.declineOutcome("""{"responseText":"nothing conclusive"}"""))
    }

    @Test
    fun `the byte array overload agrees with the string overload`() {
        val json = """{"isSuccess":false,"responseData":{"resultCode":7,"resultText":"nope"}}"""
        val fromBytes = PayabliEnvelope.declineOutcome(json.toByteArray(Charsets.UTF_8))

        // Against literals, not against the other overload: comparing the two nullable results to each
        // other passes when both are null, so it would survive declineOutcome returning null always.
        assertNotNull(fromBytes)
        assertEquals(7, fromBytes?.code)
        assertEquals("nope", fromBytes?.reason)
        assertEquals(PayabliEnvelope.declineOutcome(json)?.code, fromBytes?.code)
        assertEquals(PayabliEnvelope.declineOutcome(json)?.reason, fromBytes?.reason)
    }

    @Test
    fun `success decodes the endpoint payload out of responseData`() {
        val envelope =
            PayabliJson.format.decodeFromString(
                PayabliEnvelope.Success.serializer(DeviceConfig.serializer()),
                """{"isSuccess":true,"responseData":{"entryPoint":"my-entry"}}""",
            )
        assertEquals("my-entry", envelope.responseData?.entryPoint)
    }

    @Test
    fun `the empty payload decodes for endpoints that return only isSuccess`() {
        val envelope =
            PayabliJson.format.decodeFromString(
                PayabliEnvelope.Success.serializer(PayabliEnvelope.EmptyPayload.serializer()),
                """{"isSuccess":true}""",
            )
        assertNull(envelope.responseData)
    }

    @Test
    fun `decline outcome toString carries the code but never the reason`() {
        val rendered =
            PayabliEnvelope
                .declineOutcome("""{"isSuccess":false,"responseData":{"resultCode":51,"resultText":"do not honor"}}""")
                .toString()
        assertTrue(rendered.contains("51"))
        assertFalse(rendered.contains("do not honor"))
    }
}
