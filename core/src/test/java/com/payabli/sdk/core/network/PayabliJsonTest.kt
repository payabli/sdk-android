package com.payabli.sdk.core.network

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PayabliJsonTest {
    @Serializable
    private class Payload(
        val name: String? = null,
    )

    @Test
    fun `a well-formed body decodes`() {
        assertEquals("ok", PayabliJson.decodeOrNull(Payload.serializer(), """{"name":"ok"}""")?.name)
    }

    @Test
    fun `a malformed body returns null rather than throwing`() {
        assertNull(PayabliJson.decodeOrNull(Payload.serializer(), "not json at all"))
        assertNull(PayabliJson.decodeOrNull(Payload.serializer(), ""))
        assertNull(PayabliJson.decodeOrNull(Payload.serializer(), "{"))
    }

    @Test
    fun `an error raised inside a serializer propagates instead of reading as malformed input`() {
        // The one boundary both decline paths depend on: a JVM error is not a malformed body, and
        // must not reach a caller disguised as one.
        assertThrows(SimulatedFatalError::class.java) {
            PayabliJson.decodeOrNull(FatalSerializer, "\"any well-formed body\"")
        }
    }
}
