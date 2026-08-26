package com.payabli.sdk.taptopay

import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.enrollment.ENTRY
import com.payabli.sdk.taptopay.enrollment.RouteScript
import com.payabli.sdk.taptopay.enrollment.attestBody
import com.payabli.sdk.taptopay.enrollment.challengeBody
import com.payabli.sdk.taptopay.enrollment.configBody
import com.payabli.sdk.taptopay.enrollment.registerBody
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.network.TTPTransactionClient
import com.payabli.sdk.taptopay.network.approved
import com.payabli.sdk.taptopay.session.SessionFixture
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

private const val TRANS_ID = "12-abc"

private const val INITIATE = "/api/v2/MoneyIn/initiate"

private const val UPDATE = "/api/v2/MoneyIn/update/$TRANS_ID"

private fun script(updates: Int = 1) =
    RouteScript(
        RouteScript.CHALLENGE to listOf(challengeBody()),
        RouteScript.REGISTER to listOf(registerBody(status = "active")),
        RouteScript.ATTEST to listOf(attestBody()),
        RouteScript.CONFIG to listOf(configBody()),
        INITIATE to listOf(approved("""{"paymentTransId":"$TRANS_ID"}""")),
        UPDATE to List(updates) { "{}" },
    )

/**
 * A whole payment over fakes: the two Payabli calls, the reader between them, and what each failure does
 * to the session.
 */
class TapToPayChargeRunnerTest {
    private fun runnerOver(fixture: SessionFixture) =
        TapToPayChargeRunner(
            entry = ENTRY,
            coordinator = fixture.coordinator,
            manager = fixture.manager,
            reader = fixture.reader,
            client = TTPTransactionClient(fixture.enrollment.transport, fixture.enrollment.logger),
            store = fixture.enrollment.store,
        )

    private suspend fun readyFixture(updates: Int = 1): SessionFixture =
        SessionFixture(script(updates)).also { it.coordinator.initialize() }

    private fun details(amount: String = "12.34") = TapToPayPaymentDetails(BigDecimal(amount))

    @Test
    fun `a payment is opened, tapped and closed, in that order`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()

            val receipt =
                runnerOver(fixture).charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)

            assertEquals(TRANS_ID, receipt.paymentTransId)
            assertEquals(listOf(INITIATE, UPDATE), fixture.routes.filter { it.startsWith("/api/v2/MoneyIn") })
        }

    @Test
    fun `the reader is handed the identifier the payment was opened under`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Both fields, because the processor keys the reconciliation on them and this SDK has one
            // identifier for the two.
            val fixture = readyFixture()

            runnerOver(fixture).charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)

            assertEquals(TRANS_ID, fixture.reader.lastReadRequest?.merchantTransactionId)
            assertEquals(TRANS_ID, fixture.reader.lastReadRequest?.merchantOrderId)
        }

    @Test
    fun `a tap that never completed still closes the transaction`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Otherwise the opened payment is left standing at the paypoint with nothing to resolve it.
            val fixture = readyFixture()
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is CardReaderException.ReadFailed)
            assertTrue(UPDATE in fixture.routes)
            assertEquals(TapToPaySessionState.Ready, fixture.state)
        }

    @Test
    fun `a dead reader session expires the session so a repair can run`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()
            fixture.reader.failNextRead(CardReaderException.SessionUnusable(null))

            runCatching {
                runnerOver(fixture).charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)
            }

            assertEquals(TapToPaySessionState.SessionExpired, fixture.state)
        }

    @Test
    fun `an amount of zero opens nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()

            val failure =
                runCatching {
                    runnerOver(fixture)
                        .charge(details("0.00"), TapToPayCustomerData(), TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is IllegalArgumentException)
            assertFalse(INITIATE in fixture.routes)
        }

    @Test
    fun `a terminal that was never set up opens nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = SessionFixture(script())

            runCatching {
                runnerOver(fixture).charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)
            }

            assertFalse(INITIATE in fixture.routes)
        }
}
