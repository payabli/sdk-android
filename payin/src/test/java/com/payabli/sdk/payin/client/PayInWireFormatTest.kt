package com.payabli.sdk.payin.client

import com.payabli.sdk.core.network.PayabliJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * The response side of the casing decision: a field the service re-spells still lands.
 *
 * Each alias gets its own assertion because a typo in one is invisible — the field simply reads null, which is
 * exactly the failure this whole ticket exists to stop happening on `errors`.
 */
class PayInWireFormatTest {
    private fun transaction(body: String) = PayabliJson.format.decodeFromString(TransactionPayload.serializer(), body)

    private fun stored(body: String) = PayabliJson.format.decodeFromString(StoredMethodEnvelope.serializer(), body)

    @Test
    fun `the canonical casing decodes`() {
        val decoded =
            transaction(
                """
                {"paymentTransId":"t-1","gatewayTransId":"g-1","orderId":"o-1","method":"card","transStatus":1,
                 "paypointId":42,"totalAmount":10.00,"netAmount":9.71,"connectorName":"fiserv","payorId":7}
                """.trimIndent(),
            )

        assertEquals("t-1", decoded.paymentTransId)
        assertEquals("g-1", decoded.gatewayTransId)
        assertEquals("o-1", decoded.orderId)
        assertEquals("card", decoded.method)
        assertEquals(1, decoded.transStatus)
        assertEquals(42L, decoded.paypointId)
        assertEquals(BigDecimal("10.00"), decoded.totalAmount)
        assertEquals(BigDecimal("9.71"), decoded.netAmount)
        assertEquals("fiserv", decoded.connectorName)
        assertEquals(7L, decoded.payorId)
    }

    @Test
    fun `all lower case decodes`() {
        val decoded =
            transaction(
                """
                {"paymenttransid":"t-1","gatewaytransid":"g-1","orderid":"o-1","transstatus":1,"paypointid":42,
                 "totalamount":10.00,"netamount":9.71,"connectorname":"fiserv","payorid":7}
                """.trimIndent(),
            )

        assertEquals("t-1", decoded.paymentTransId)
        assertEquals("g-1", decoded.gatewayTransId)
        assertEquals("o-1", decoded.orderId)
        assertEquals(1, decoded.transStatus)
        assertEquals(42L, decoded.paypointId)
        assertEquals(BigDecimal("10.00"), decoded.totalAmount)
        assertEquals(BigDecimal("9.71"), decoded.netAmount)
        assertEquals("fiserv", decoded.connectorName)
        assertEquals(7L, decoded.payorId)
    }

    @Test
    fun `pascal case decodes`() {
        val decoded =
            transaction(
                """
                {"PaymentTransId":"t-1","GatewayTransId":"g-1","OrderId":"o-1","Method":"card","TransStatus":1,
                 "PaypointId":42,"TotalAmount":10.00,"NetAmount":9.71,"ConnectorName":"fiserv","PayorId":7}
                """.trimIndent(),
            )

        assertEquals("t-1", decoded.paymentTransId)
        assertEquals("card", decoded.method)
        assertEquals(BigDecimal("10.00"), decoded.totalAmount)
        assertEquals(7L, decoded.payorId)
    }

    @Test
    fun `two spellings of one field in one body leave the last one standing`() {
        // An alias loses to a declared name when deciding which property a key belongs to. When both keys
        // appear in one object they resolve to the same property, and the later one overwrites the earlier.
        // So the documented precedence between a name and an alias does not decide which value survives.
        assertEquals("alias", transaction("""{"paymentTransId":"declared","paymenttransid":"alias"}""").paymentTransId)
        assertEquals(
            "declared",
            transaction("""{"paymenttransid":"alias","paymentTransId":"declared"}""").paymentTransId,
        )
    }

    @Test
    fun `an unknown field is ignored rather than fatal`() {
        // The service adds response fields without notice, and a decode that failed on one would take the
        // whole transaction down with it.
        val decoded = transaction("""{"paymentTransId":"t-1","somethingNew":{"nested":true}}""")

        assertEquals("t-1", decoded.paymentTransId)
    }

    @Test
    fun `the stored-method envelope decodes in three casings`() {
        val bodies =
            listOf(
                """{"isSuccess":true,"responseText":"ok","responseData":{"referenceId":"tok-1","resultCode":1}}""",
                """{"isSuccess":true,"responsetext":"ok","responsedata":{"referenceid":"tok-1","resultcode":1}}""",
                """{"isSuccess":true,"ResponseText":"ok","ResponseData":{"ReferenceId":"tok-1","ResultCode":1}}""",
            )

        bodies.forEach { body ->
            val decoded = stored(body)
            assertEquals(body, "ok", decoded.responseText)
            assertEquals(body, "tok-1", decoded.responseData?.referenceId)
            assertEquals(body, 1, decoded.responseData?.resultCode)
        }
    }

    @Test
    fun `an absent field is null rather than a decode failure`() {
        val decoded = transaction("{}")

        assertNull(decoded.paymentTransId)
        assertNull(decoded.totalAmount)
    }
}
