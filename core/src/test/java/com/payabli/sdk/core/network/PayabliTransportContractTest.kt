package com.payabli.sdk.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ticket's acceptance criterion: a fake transport substitutes for the real one, and the contract
 * compiles with no Android framework dependency. No `android.*` import appears in this file or in the
 * production types it exercises, so it runs on the plain JVM without Robolectric.
 */
class PayabliTransportContractTest {
    /** Stands in for a real endpoint client, none of which exist yet. */
    private class ChargeClient(
        private val transport: PayabliTransport,
    ) {
        suspend fun charge(amount: Int): PayabliV2Envelope<ChargeResult> =
            transport.execute(
                PayabliRequest.json(
                    method = HttpMethod.POST,
                    path = "/api/MoneyIn/getpaid",
                    body = ChargeBody(amount),
                    bodySerializer = ChargeBody.serializer(),
                ),
                ChargeResult.serializer(),
            )
    }

    @Serializable
    private class ChargeBody(
        val amount: Int,
    )

    @Serializable
    private class ChargeResult(
        val paymentTransId: String,
    )

    @Test
    fun `endpoint client works against a fake transport`() =
        runTest {
            val transport =
                FakePayabliTransport()
                    .enqueue(body = """{"code":"A01","data":{"paymentTransId":"txn-1"}}""")

            val envelope = ChargeClient(transport).charge(amount = 1250)

            assertTrue(envelope.isApproved)
            assertEquals("txn-1", envelope.payload?.paymentTransId)
        }

    @Test
    fun `client sends the method, path and json content type the transport receives`() =
        runTest {
            val transport = FakePayabliTransport().enqueue(body = """{"code":"A01"}""")

            ChargeClient(transport).charge(amount = 700)

            val sent = transport.recorded.single()
            assertEquals(HttpMethod.POST, sent.method)
            assertEquals("/api/MoneyIn/getpaid", sent.path)
            assertEquals("application/json", sent.headers["Content-Type"])
            assertEquals("""{"amount":700}""", sent.body?.toString(Charsets.UTF_8))
        }

    @Test
    fun `raw execute returns the response untouched, mapping no status to an error`() =
        runTest {
            val transport = FakePayabliTransport().enqueue(statusCode = 402, body = "declined")

            val response = transport.execute(PayabliRequest(HttpMethod.GET, "/x"))

            // Status-to-error mapping is the caller's job, deliberately not the transport's.
            assertEquals(402, response.statusCode)
            assertEquals("declined", response.bodyAsText())
        }

    @Test
    fun `absent envelope payload decodes as null rather than throwing`() =
        runTest {
            val transport = FakePayabliTransport().enqueue(body = """{"code":"D05","reason":"declined"}""")

            val envelope = ChargeClient(transport).charge(amount = 1)

            assertTrue(envelope.isDeclined)
            assertNull(envelope.payload)
        }
}
