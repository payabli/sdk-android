package com.payabli.example.app.sdk

import com.payabli.sdk.payin.model.PayInTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which reads tell the screen the transaction is reversed, or still captured. */
class PayInReadOutcomeTest {
    @Test
    fun `a read whose status is voided says so`() {
        val outcome = Result.success(transaction(transStatus = 5)).toReadOutcome() as PayInReadOutcome.Read

        assertTrue(outcome.isVoided)
        assertFalse(outcome.isCaptured)
    }

    @Test
    fun `a read whose status is captured says so`() {
        val outcome = Result.success(transaction(transStatus = 1)).toReadOutcome() as PayInReadOutcome.Read

        assertTrue(outcome.isCaptured)
        assertFalse(outcome.isVoided)
    }

    @Test
    fun `a read with any other status does not`() {
        listOf(1, 2, 10, 11, -2, 0, 99, null).forEach { status ->
            val outcome = Result.success(transaction(transStatus = status)).toReadOutcome() as PayInReadOutcome.Read

            assertFalse("status $status", outcome.isVoided)
            assertEquals("status $status", status == 1, outcome.isCaptured)
            assertEquals(status, outcome.statusNumber)
        }
    }

    private fun transaction(transStatus: Int?) =
        PayInTransaction(
            paymentTransId = "101-abc",
            gatewayTransId = null,
            orderId = null,
            method = "card",
            transStatus = transStatus,
            paypointId = 42,
            totalAmount = null,
            netAmount = null,
            feeAmount = null,
            surchargeFee = null,
            connectorName = null,
            customerId = null,
        )
}
