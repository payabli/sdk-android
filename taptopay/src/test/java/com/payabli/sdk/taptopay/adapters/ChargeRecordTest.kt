package com.payabli.sdk.taptopay.adapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the transaction client forwards, which has to be a JSON object and has to hold no card. */
class ChargeRecordTest {
    @Test
    fun `a record with nothing in it is still an object`() {
        assertEquals("{}", ChargeRecord().encoded())
    }

    @Test
    fun `only the fields the processor answered are written`() {
        val encoded =
            ChargeRecord(
                gatewayResponse = GatewayResponseRecord(transactionState = "CAPTURED"),
                cardNetwork = "VISA",
            ).encoded()

        assertEquals("""{"gatewayResponse":{"transactionState":"CAPTURED"},"cardNetwork":"VISA"}""", encoded)
    }

    @Test
    fun `the processor's own nesting is kept`() {
        val encoded =
            ChargeRecord(
                gatewayResponse =
                    GatewayResponseRecord(
                        transactionProcessingDetails = TransactionProcessingRecord(transactionId = "t-1"),
                    ),
                paymentReceipt = PaymentReceiptRecord(ProcessorResponseRecord(approvalCode = "OK100")),
            ).encoded()

        assertTrue(encoded, encoded.contains(""""transactionProcessingDetails":{"transactionId":"t-1"}"""))
        assertTrue(encoded, encoded.contains(""""processorResponseDetails":{"approvalCode":"OK100"}"""))
    }
}
