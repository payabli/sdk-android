package com.payabli.sdk.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayabliV2EnvelopeTest {
    @Serializable
    private class Payload(
        val paymentTransId: String,
    )

    private fun decode(json: String): PayabliV2Envelope<Payload> =
        PayabliJson.format.decodeFromString(PayabliV2Envelope.serializer(Payload.serializer()), json)

    @Test
    fun `code prefix A is approved and D is declined`() {
        assertTrue(decode("""{"code":"A01"}""").isApproved)
        assertFalse(decode("""{"code":"A01"}""").isDeclined)
        assertTrue(decode("""{"code":"D05"}""").isDeclined)
        assertFalse(decode("""{"code":"D05"}""").isApproved)
    }

    @Test
    fun `an unrecognised code family is neither approved nor declined`() {
        val envelope = decode("""{"code":"E99"}""")
        assertFalse(envelope.isApproved)
        assertFalse(envelope.isDeclined)
    }

    @Test
    fun `the wire key stays data even though the property is payload`() {
        // The rename is Kotlin-side only; @SerialName pins the wire contract.
        val envelope = decode("""{"code":"A01","data":{"paymentTransId":"txn-9"}}""")
        assertEquals("txn-9", envelope.payload?.paymentTransId)
    }

    @Test
    fun `optional envelope fields decode when present and are null when absent`() {
        val full = decode("""{"code":"A01","reason":"ok","explanation":"why","action":"none"}""")
        assertEquals("ok", full.reason)
        assertEquals("why", full.explanation)
        assertEquals("none", full.action)

        val bare = decode("""{"code":"A01"}""")
        assertNull(bare.reason)
        assertNull(bare.explanation)
        assertNull(bare.action)
        assertNull(bare.payload)
    }

    @Test
    fun `a body missing code is a decode failure`() {
        // `code` is the one non-optional field. Asserted on SerializationException rather than
        // MissingFieldException, which is still experimental.
        val thrown =
            runCatching { decode("""{"reason":"no code here"}""") }.exceptionOrNull()
        assertTrue(thrown is SerializationException)
        assertTrue(thrown?.message?.contains("code") == true)
    }

    @Test
    fun `unknown server fields are ignored`() {
        // Payabli adds response fields without notice; a decode must survive them.
        val envelope = decode("""{"code":"A01","token":"ignored","futureField":{"a":1}}""")
        assertEquals("A01", envelope.code)
    }

    @Test
    fun `toString carries the code but never the payload or reason`() {
        val json = """{"code":"D05","reason":"insufficient funds","data":{"paymentTransId":"t"}}"""
        val rendered = decode(json).toString()
        assertTrue(rendered.contains("D05"))
        assertFalse(rendered.contains("insufficient funds"))
        assertFalse(rendered.contains("paymentTransId"))
    }

    @Test
    fun `the envelope composes with a primitive payload serializer`() {
        val envelope =
            PayabliJson.format.decodeFromString(
                PayabliV2Envelope.serializer(String.serializer()),
                """{"code":"A01","data":"plain"}""",
            )
        assertEquals("plain", envelope.payload)
    }
}
