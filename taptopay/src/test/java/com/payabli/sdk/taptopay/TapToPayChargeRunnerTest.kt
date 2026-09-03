package com.payabli.sdk.taptopay

import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.adapters.CardReaderFailure
import com.payabli.sdk.taptopay.adapters.ReaderFailureKind
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
import com.payabli.sdk.taptopay.session.MINTED_KEY
import com.payabli.sdk.taptopay.session.SessionFixture
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import kotlinx.coroutines.CancellationException
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

private fun script(
    updates: Int = 1,
    opens: Int = 1,
) = RouteScript(
    RouteScript.CHALLENGE to listOf(challengeBody()),
    RouteScript.REGISTER to listOf(registerBody(status = "active")),
    RouteScript.ATTEST to listOf(attestBody()),
    RouteScript.CONFIG to listOf(configBody()),
    INITIATE to List(opens) { approved("""{"paymentTransId":"$TRANS_ID"}""") },
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
            keys = fixture.keys,
        )

    private suspend fun readyFixture(
        updates: Int = 1,
        opens: Int = 1,
    ): SessionFixture = SessionFixture(script(updates, opens)).also { it.coordinator.initialize() }

    private fun details(amount: String = "12.34") = TapToPayPaymentDetails(BigDecimal(amount))

    @Test
    fun `a withdrawn charge is not reported as a failed one`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Driven through the real runner rather than by calling the reporter, because what is asserted
            // is that this path does not emit: a test that invoked the reporter directly would stay green
            // however the runner behaved.
            //
            // The read raises the cancellation rather than the job being cancelled from outside. Same
            // branch, and it keeps the uncancellable close - which runs either way - out of the test's
            // own completion, where it otherwise deadlocks against `cancelAndJoin`.
            val recorded = mutableListOf<String>()
            TelemetryRecorders.install { event, _ -> recorded += event }
            try {
                val fixture = readyFixture(updates = 2)
                fixture.reader.failNextRead(CancellationException("the host withdrew"))

                val outcome =
                    runCatching {
                        runnerOver(fixture)
                            .charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)
                    }.exceptionOrNull()

                assertTrue(outcome.toString(), outcome is CancellationException)
                assertFalse(
                    "a withdrawn charge was recorded as a failure: $recorded",
                    TelemetryEvents.TTP_CHARGE_FAILED in recorded,
                )
                // Withdrawing does not leave the payment open, which is the other half of the contract.
                assertTrue(UPDATE in fixture.routes)
            } finally {
                TelemetryRecorders.clear()
            }
        }

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

    /** The key one opening carried, from the request itself rather than from the store that held it. */
    private fun SessionFixture.keySent(attempt: Int = 0): String? =
        requestsTo(INITIATE)[attempt].headers["idempotencyKey"]

    @Test
    fun `a charge names its attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()

            runnerOver(fixture).charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)

            assertEquals("$MINTED_KEY-1", fixture.keySent())
        }

    @Test
    fun `a charge that settled leaves the next one to name its own attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Two sales, two attempts. Reusing the key here would refuse the second payment.
            val fixture = readyFixture(updates = 2, opens = 2)
            val runner = runnerOver(fixture)

            runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)
            runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)

            assertEquals("$MINTED_KEY-1", fixture.keySent(0))
            assertEquals("$MINTED_KEY-2", fixture.keySent(1))
        }

    @Test
    fun `a tap that was captured and could not be closed keeps its attempt for the retry`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The sharp one. The reader captured the sale and the close never landed, so the obvious
            // recovery is to charge again — and that opens a second transaction unless it repeats the key.
            val fixture = readyFixture(updates = 0, opens = 2)
            val runner = runnerOver(fixture)

            runCatching { runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null) }
            runCatching { runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null) }

            assertEquals("$MINTED_KEY-1", fixture.keySent(0))
            assertEquals("the retry named a second attempt", "$MINTED_KEY-1", fixture.keySent(1))
        }

    @Test
    fun `a tap that never happened lets its attempt go, because the payer has not paid`() =
        runTest(timeout = TEST_TIMEOUT) {
            // No card was read, so nothing was captured and what comes next is a new sale. Holding the key
            // would refuse it.
            val fixture = readyFixture(updates = 2, opens = 2)
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))
            val runner = runnerOver(fixture)

            runCatching { runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null) }
            runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)

            assertEquals("$MINTED_KEY-2", fixture.keySent(1))
        }

    @Test
    fun `a second terminal for one entry point repeats the attempt the first left unsettled`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A terminal is built per call and caches nothing, so the retry usually comes from a second one
            // after the screen holding the first was rebuilt. A key on the instance would be gone.
            val fixture = readyFixture(updates = 0, opens = 2)

            runCatching { runnerOver(fixture).charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null) }
            runCatching { runnerOver(fixture).charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null) }

            assertEquals("$MINTED_KEY-1", fixture.keySent(0))
            assertEquals("the second terminal named its own attempt", "$MINTED_KEY-1", fixture.keySent(1))
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
    fun `a denial during the tap is reported as a denial, not as a spent session`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The two mean different things to a caller. A spent session invites the retry that a repair
            // makes work; a denial is the vendor refusing the handset, and no retry reaches it.
            val fixture = readyFixture(updates = 2)
            fixture.reader.failNextRead(
                CardReaderException.DeviceDenied(
                    CardReaderFailure(ReaderFailureKind.DEVICE_DENIED, code = "677"),
                ),
            )

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is CardReaderException.DeviceDenied)
            // Expired rather than left ready, so the next charge does not open a transaction before
            // finding out. DEVICE_INELIGIBLE is unreachable from Ready and is landed at the repair.
            assertEquals(TapToPaySessionState.SessionExpired, fixture.state)
            assertTrue(UPDATE in fixture.routes)
        }

    @Test
    fun `an amount that rounds away to nothing opens nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            // More than zero as supplied and `0.00` on the wire, so checking the raw value opened a payment
            // the service is asked to take as nothing.
            val fixture = readyFixture()

            val failure =
                runCatching {
                    runnerOver(fixture)
                        .charge(details("0.001"), TapToPayCustomerData(), TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is IllegalArgumentException)
            assertFalse(INITIATE in fixture.routes)
        }

    @Test
    fun `an amount the SDK cannot send opens nothing, rather than throwing while encoding`() =
        runTest(timeout = TEST_TIMEOUT) {
            // `setScale` raises ArithmeticException at the extremes of the exponent, so without the guard
            // this failed inside the request encoder instead of as a refused argument.
            val fixture = readyFixture()

            val failure =
                runCatching {
                    runnerOver(fixture).charge(
                        TapToPayPaymentDetails(BigDecimal("1E-2147483647")),
                        TapToPayCustomerData(),
                        TapToPayInvoiceData(),
                        null,
                    )
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is IllegalArgumentException)
            assertFalse(INITIATE in fixture.routes)
        }

    @Test
    fun `the reader is asked for the amount the paypoint recorded`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The serializer rounds what initiate sent, so a caller's own scale asks the card for one
            // amount and the paypoint to record another.
            val fixture = readyFixture()

            runnerOver(fixture)
                .charge(details("12.345"), TapToPayCustomerData(), TapToPayInvoiceData(), null)

            assertEquals(BigDecimal("12.35"), fixture.reader.lastReadRequest?.amount)
        }

    @Test
    fun `a service fee gets the checks the amount gets`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()

            val negative =
                runCatching {
                    runnerOver(fixture).charge(
                        TapToPayPaymentDetails(BigDecimal("10.00"), serviceFee = BigDecimal("-0.01")),
                        TapToPayCustomerData(),
                        TapToPayInvoiceData(),
                        null,
                    )
                }.exceptionOrNull()
            val unsendable =
                runCatching {
                    runnerOver(fixture).charge(
                        TapToPayPaymentDetails(BigDecimal("10.00"), serviceFee = BigDecimal("1E+2147483647")),
                        TapToPayCustomerData(),
                        TapToPayInvoiceData(),
                        null,
                    )
                }.exceptionOrNull()

            assertTrue(negative.toString(), negative is IllegalArgumentException)
            assertTrue(unsendable.toString(), unsendable is IllegalArgumentException)
            assertFalse(INITIATE in fixture.routes)
        }

    @Test
    fun `a ready session whose device record is gone does not stay ready`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The store answers null when the record is lost after the session came up. Left ready, isReady
            // stays true and every retry reaches the same line.
            val fixture = readyFixture()
            fixture.enrollment.store.clear(ENTRY)

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is IllegalStateException)
            assertEquals(TapToPaySessionState.SessionExpired, fixture.state)
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
