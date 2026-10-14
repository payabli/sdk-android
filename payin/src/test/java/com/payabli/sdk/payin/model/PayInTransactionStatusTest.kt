package com.payabli.sdk.payin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The status number a transaction carries, read by name rather than by position. */
class PayInTransactionStatusTest {
    @Test
    fun `every status number the SDK names reads as its status`() {
        val expected =
            mapOf(
                10 to PayInTransactionStatus.Initiated,
                11 to PayInTransactionStatus.Authorized,
                1 to PayInTransactionStatus.Captured,
                2 to PayInTransactionStatus.Declined,
                3 to PayInTransactionStatus.Declined,
                5 to PayInTransactionStatus.Voided,
                -2 to PayInTransactionStatus.Blocked,
                -1 to PayInTransactionStatus.Failed,
                0 to PayInTransactionStatus.Failed,
            )

        assertEquals(expected, expected.keys.associateWith(PayInTransactionStatus::of))
    }

    @Test
    fun `a number the SDK does not name is kept rather than guessed at`() {
        listOf(4, 6, 12, 99, -3, -4, -5, -6, -7, Int.MAX_VALUE, Int.MIN_VALUE).forEach {
            assertEquals(PayInTransactionStatus.Other(it), PayInTransactionStatus.of(it))
        }
    }

    @Test
    fun `two unnamed numbers are told apart by the number`() {
        assertEquals(PayInTransactionStatus.Other(4).hashCode(), PayInTransactionStatus.Other(4).hashCode())
        assertNotEquals(PayInTransactionStatus.Other(4), PayInTransactionStatus.Other(6))
        assertNotEquals(PayInTransactionStatus.Other(1), PayInTransactionStatus.Captured)
        assertEquals("Other(raw=4)", PayInTransactionStatus.Other(4).toString())
    }

    @Test
    fun `a transaction reads its status from the number it carries`() {
        assertEquals(PayInTransactionStatus.Voided, transaction(transStatus = 5).status)
        assertEquals(5, transaction(transStatus = 5).transStatus)
    }

    @Test
    fun `a transaction with no status number has no status`() {
        assertNull(transaction(transStatus = null).status)
    }

    private fun transaction(transStatus: Int?) =
        PayInTransaction(
            paymentTransId = "101-abc",
            gatewayTransId = null,
            orderId = null,
            method = null,
            transStatus = transStatus,
            paypointId = null,
            totalAmount = null,
            netAmount = null,
            feeAmount = null,
            surchargeFee = null,
            connectorName = null,
            customerId = null,
        )
}
