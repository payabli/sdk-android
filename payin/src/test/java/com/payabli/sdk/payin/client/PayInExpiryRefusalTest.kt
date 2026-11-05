package com.payabli.sdk.payin.client

import com.payabli.sdk.payin.model.ExpiryValue
import com.payabli.sdk.payin.model.PayInCardData
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInInstrument
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInPaymentMethod
import com.payabli.sdk.payin.model.PayInRequest
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

/**
 * Every call that sends a card refuses an expiry it can't send, before the transport sees a request.
 */
class PayInExpiryRefusalTest {
    private val timeout = 10.seconds

    private fun card(
        month: Int,
        year: Int,
    ) = PayInCardData(
        cardNumber = TEST_PAN,
        expiryMonth = month,
        expiryYear = year,
        securityCode = TEST_SECURITY_CODE,
        holderName = "Integration Test",
        postalCode = "22039",
    )

    private fun request(card: PayInCardData) =
        PayInRequest(
            paymentMethod = PayInPaymentMethod.Card(card),
            options = PayInTransactionOptions(PayInPaymentDetails(totalAmount = BigDecimal("10.00"))),
        )

    /** Each call that sends a card, against [transport]. */
    private fun calls(transport: FakePayInTransport): Map<String, suspend (PayInCardData) -> Unit> =
        mapOf(
            "capture" to { card -> MoneyInClient(transport, RecordingSdkLogger()).capture("e", request(card)) },
            "authorize" to { card -> MoneyInClient(transport, RecordingSdkLogger()).authorize("e", request(card)) },
            "storeMethod" to { card ->
                TokenStorageClient(transport, RecordingSdkLogger()).storeMethod("e", PayInInstrument.Card(card))
            },
        )

    private class Refusal(
        val name: String,
        val month: Int,
        val year: Int,
        val reason: String,
    )

    private fun lastMonth(): Pair<Int, Int> {
        val today = ExpiryValue.today()
        return if (today.month == 1) 12 to today.year - 1 else today.month - 1 to today.year
    }

    @Test
    fun `an expiry the service can't take is refused at the expiry field, before anything is sent`() =
        runTest(timeout = timeout) {
            val farYear = ExpiryValue.today().year + 5
            val (lastMonth, lastMonthYear) = lastMonth()
            val refused =
                listOf(
                    Refusal("month 0", 0, farYear, "The expiry month is not 1 to 12"),
                    Refusal("month 13", 13, farYear, "The expiry month is not 1 to 12"),
                    Refusal("two-digit year", 12, 30, "The expiry year is not four digits"),
                    Refusal("five-digit year", 12, 20300, "The expiry year is not four digits"),
                    Refusal("year after 2099", 1, 2100, "The expiry year is after 2099"),
                    Refusal("last month", lastMonth, lastMonthYear, "The card has expired"),
                )

            refused.forEach { case ->
                val transport = FakePayInTransport.failingWith(IOException("not expected to be reached"))
                calls(transport).forEach { (call, send) ->
                    val failure = runCatching { send(card(case.month, case.year)) }.exceptionOrNull()

                    assertTrue("$call, ${case.name}: $failure", failure is PayInException.InvalidInput)
                    val field = (failure as PayInException.InvalidInput).field
                    assertEquals("$call, ${case.name}", "paymentMethod.cardexp", field)
                    assertEquals("$call, ${case.name}", case.reason, failure.reason)
                    assertEquals("$call, ${case.name} reached the transport", 0, transport.count)
                }
            }
        }

    @Test
    fun `a card expiring this month is sent`() =
        runTest(timeout = timeout) {
            // A card is good through the last day of its expiry month.
            val today = ExpiryValue.today()
            assertSent(today.month, today.year)
        }

    @Test
    fun `a card expiring in December 2099 is sent`() = runTest(timeout = timeout) { assertSent(12, 2099) }

    private suspend fun assertSent(
        month: Int,
        year: Int,
    ) {
        listOf("capture", "authorize", "storeMethod").forEach { call ->
            val transport = FakePayInTransport.failingWith(IOException("sent"))
            val failure = runCatching { calls(transport).getValue(call)(card(month, year)) }.exceptionOrNull()

            assertFalse("$call, $month/$year: $failure", failure is PayInException.InvalidInput)
            assertEquals("$call, $month/$year did not send", 1, transport.count)
        }
    }
}
