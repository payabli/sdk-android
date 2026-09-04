package com.payabli.sdk.taptopay

import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
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
import com.payabli.sdk.taptopay.network.TTPRoutes
import com.payabli.sdk.taptopay.network.TTPTransactionClient
import com.payabli.sdk.taptopay.network.approved
import com.payabli.sdk.taptopay.session.MINTED_KEY
import com.payabli.sdk.taptopay.session.SessionFixture
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

private const val TRANS_ID = "12-abc"

private const val INITIATE = "/api/v2/MoneyIn/initiate"

private const val UPDATE = "/api/v2/MoneyIn/update/$TRANS_ID"

/** The reader's mark in the shared trace, which is how a second tap is counted. */
private const val READ = "reader:read"

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
 * The same script with the close under the test's control.
 *
 * [closeFails] is read per call rather than fixed, so one run can fail the close and then let a later one
 * through. A failing close is answered 500, which is the status the close retries, so it costs three of
 * [closes].
 */
private fun scriptWithCloseControl(
    opens: Int = 1,
    closes: Int,
    closeFails: () -> Boolean,
) = RouteScript(
    RouteScript.CHALLENGE to listOf(challengeBody()),
    RouteScript.REGISTER to listOf(registerBody(status = "active")),
    RouteScript.ATTEST to listOf(attestBody()),
    RouteScript.CONFIG to listOf(configBody()),
    INITIATE to List(opens) { approved("""{"paymentTransId":"$TRANS_ID"}""") },
    UPDATE to List(closes) { "{}" },
    statusFor = { path -> if (path == UPDATE && closeFails()) 500 else 200 },
)

/**
 * The fixture's transport, with a hook that runs before the close is sent.
 *
 * Before, because that is where a test has to be to withdraw a caller while the close is in flight. The
 * fixture's own fake takes a responder that cannot suspend, so it cannot hold one open.
 */
private class GatedCloseTransport(
    private val inner: PayabliTransport,
    private val onClose: suspend () -> Unit,
) : PayabliTransport {
    override suspend fun execute(request: PayabliRequest): PayabliResponse {
        if (request.route == TTPRoutes.UPDATE) onClose()
        return inner.execute(request)
    }

    override suspend fun <T> execute(
        request: PayabliRequest,
        payloadSerializer: KSerializer<T>,
    ): PayabliV2Envelope<T> = inner.execute(request, payloadSerializer)
}

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

    private fun runnerGatedOnClose(
        fixture: SessionFixture,
        onClose: suspend () -> Unit,
    ) = TapToPayChargeRunner(
        entry = ENTRY,
        coordinator = fixture.coordinator,
        manager = fixture.manager,
        reader = fixture.reader,
        client =
            TTPTransactionClient(
                GatedCloseTransport(fixture.enrollment.transport, onClose),
                fixture.enrollment.logger,
            ),
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

            assertTrue(failure.toString(), failure is TapToPayException)
            assertTrue(failure.toString(), failure?.cause is CardReaderException.ReadFailed)
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
    fun `a close the service refused keeps the attempt, because the reader already captured`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The service answering 400 says the close was rejected, not that the sale was not taken: the
            // reader captured before `update` was ever sent. Releasing the key on an answer that arrives
            // after the capture is what lets the next charge take the money again.
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to List(2) { approved("""{"paymentTransId":"$TRANS_ID"}""") },
                        UPDATE to List(2) { "" },
                        statusFor = { if (it == UPDATE) 400 else 200 },
                    ),
                ).also { it.coordinator.initialize() }
            val runner = runnerOver(fixture)

            runCatching { runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null) }
            runCatching { runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null) }

            assertEquals("$MINTED_KEY-1", fixture.keySent(0))
            assertEquals(
                "the retry named a second attempt after the sale was captured",
                "$MINTED_KEY-1",
                fixture.keySent(1),
            )
        }

    @Test
    fun `a tap that failed lets its attempt go once the close is recorded`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Not because the read failed — that is no proof the sale was not captured — but because the
            // service has recorded this transaction as failed. Its outcome is no longer in doubt, so the
            // next sale needs its own attempt and holding the key would refuse it.
            val fixture = readyFixture(updates = 2, opens = 2)
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))
            val runner = runnerOver(fixture)

            runCatching { runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null) }
            runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)

            assertEquals("$MINTED_KEY-2", fixture.keySent(1))
        }

    @Test
    fun `a tap that failed and could not be closed keeps its attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The transaction is open and nothing recorded what became of it, so the sale may have been
            // captured. A fresh key on the next charge would take the money again.
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to List(2) { approved("""{"paymentTransId":"$TRANS_ID"}""") },
                        UPDATE to List(6) { "" },
                        statusFor = { if (it == UPDATE) 500 else 200 },
                    ),
                ).also { it.coordinator.initialize() }
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))
            val runner = runnerOver(fixture)

            runCatching { runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null) }
            runCatching { runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null) }

            assertEquals("$MINTED_KEY-1", fixture.keySent(0))
            assertEquals("the retry named a second attempt", "$MINTED_KEY-1", fixture.keySent(1))
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

            assertTrue(failure.toString(), failure?.cause is CardReaderException.DeviceDenied)
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
    fun `an amount past what the money decimal carries opens nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Rounds cleanly and is still unsendable: the digit bounds pass it, and only the mantissa
            // check refuses it. Without that it reaches the wire and the service refuses it, which spends
            // a reader session on a charge that was never sendable.
            val fixture = readyFixture()
            val beyondTheMantissa = BigDecimal("79228162514264337593543950336")

            val failure =
                runCatching {
                    runnerOver(fixture).charge(
                        TapToPayPaymentDetails(beyondTheMantissa),
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
    fun `a cancellation after the card is taken still closes the transaction`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The window this guards: `startReading` has returned, so the processor holds the card, and
            // the closing call is the only thing that tells the service. Cancelling from inside the read
            // puts the job in that state deterministically, rather than racing a timer against it.
            var charging: Job? = null
            val fixture =
                SessionFixture(script(updates = 1), readGate = { charging?.cancel() })
                    .also { it.coordinator.initialize() }

            // A child job, so the cancellation lands on the charge rather than on the test itself.
            charging =
                launch {
                    runCatching {
                        runnerOver(fixture).charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)
                    }
                }
            charging.join()

            // What the caller is told is not the point and is not asserted: the close runs to completion,
            // so the charge may simply return. The guarantee is that the service was told about a card the
            // processor has already taken, because the alternative is a charge nothing can reconcile.
            assertTrue(fixture.routes.toString(), UPDATE in fixture.routes)
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

    @Test
    fun `a tap that failed names the payment it opened`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The payment exists at the paypoint from the moment it is opened, and this is the caller's only
            // handle on it.
            val fixture = readyFixture()
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertEquals(TRANS_ID, (failure as TapToPayException).paymentTransId)
            assertFalse("the card was never charged, so nothing may say it was", failure.captured)
        }

    @Test
    fun `a failure before the payment is opened names none`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A payment that was never opened must not be reported as one, or a caller reconciles a
            // transaction that does not exist.
            val fixture = SessionFixture(script())

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertNull((failure as TapToPayException).paymentTransId)
            assertFalse(failure.captured)
        }

    @Test
    fun `a close that failed says the card was charged`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The sharp case: the money has moved and charging again takes it twice, so a caller has to be
            // able to tell this apart from a payment that never happened.
            val fixture =
                SessionFixture(scriptWithCloseControl(closes = 3) { true })
                    .also { it.coordinator.initialize() }

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertEquals(TRANS_ID, (failure as TapToPayException).paymentTransId)
            assertTrue("the money moved and the failure did not say so", failure.captured)
        }

    @Test
    fun `a captured payment is closed without a second tap or a second payment`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Three answers for the close that gives up, one for the close that lands.
            var closeFails = true
            val fixture =
                SessionFixture(scriptWithCloseControl(closes = 4) { closeFails })
                    .also { it.coordinator.initialize() }
            val runner = runnerOver(fixture)
            val failure =
                runCatching {
                    runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)
                }.exceptionOrNull() as TapToPayException
            val readsBefore = fixture.enrollment.trace.count { it == READ }

            closeFails = false
            runner.closeCaptured(failure.paymentTransId!!)

            assertEquals(
                "the card was read again",
                readsBefore,
                fixture.enrollment.trace.count { it == READ },
            )
            assertEquals("a second payment was opened", 1, fixture.routes.count { it == INITIATE })
        }

    @Test
    fun `withdrawing does not abandon a close that is already running`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The card has been charged by this point, so a close dropped half way leaves the payment open
            // with nobody holding the answer, which is the state this whole path exists to avoid.
            val fixture = readyFixture()
            val atClose = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val runner =
                runnerGatedOnClose(fixture) {
                    atClose.complete(Unit)
                    release.await()
                }

            val charging =
                async { runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null) }
            atClose.await()
            charging.cancel()
            release.complete(Unit)
            runCatching { charging.await() }

            assertTrue("the close was abandoned when the caller withdrew", UPDATE in fixture.routes)
        }

    @Test
    fun `a withdrawal during a later close unwinds as one, not as a failure`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Converting it would report a withdrawn caller as a failed payment, and would hide the
            // cancellation from the facade, which reads the type to decide what to rethrow.
            // Three answers for the close that gives up, which is what leaves a payment held to close later.
            var closeFails = true
            var withdrawOnClose = false
            val fixture =
                SessionFixture(scriptWithCloseControl(closes = 3) { closeFails })
                    .also { it.coordinator.initialize() }
            val runner =
                runnerGatedOnClose(fixture) {
                    if (withdrawOnClose) throw CancellationException("the host withdrew")
                }
            runCatching { runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null) }

            closeFails = false
            withdrawOnClose = true
            val withdrawn = runCatching { runner.closeCaptured(TRANS_ID) }.exceptionOrNull()

            assertTrue(withdrawn.toString(), withdrawn is CancellationException)
        }

    @Test
    fun `a payment that closed is no longer held`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Nothing is kept once the close lands: the reader's answer carries the card's expiry and the
            // token the processor minted.
            val fixture = readyFixture()
            val runner = runnerOver(fixture)
            val receipt = runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)

            val refusal = runCatching { runner.closeCaptured(receipt.paymentTransId) }.exceptionOrNull()

            assertTrue(refusal.toString(), refusal is IllegalArgumentException)
        }

    @Test
    fun `opening a payment drops the one held before it`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Three for the close that gives up, one for the second payment's close after its tap failed.
            var closeFails = true
            val fixture =
                SessionFixture(scriptWithCloseControl(opens = 2, closes = 4) { closeFails })
                    .also { it.coordinator.initialize() }
            val runner = runnerOver(fixture)
            runCatching { runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null) }

            closeFails = false
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))
            runCatching { runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null) }

            val refusal = runCatching { runner.closeCaptured(TRANS_ID) }.exceptionOrNull()
            assertTrue(refusal.toString(), refusal is IllegalArgumentException)
        }
}
