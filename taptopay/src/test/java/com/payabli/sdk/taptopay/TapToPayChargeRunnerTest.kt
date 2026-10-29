package com.payabli.sdk.taptopay

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.model.PayabliAttempt
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.model.PayabliRetryAfter
import com.payabli.sdk.core.model.PayabliValidationException
import com.payabli.sdk.core.network.PayabliRequest
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.PayabliV2Envelope
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.adapters.CardReaderFailure
import com.payabli.sdk.taptopay.adapters.ReaderFailureKind
import com.payabli.sdk.taptopay.enrollment.AttestedDevice
import com.payabli.sdk.taptopay.enrollment.DeviceActivationException
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
import com.payabli.sdk.taptopay.network.TTPTransactionException
import com.payabli.sdk.taptopay.network.approved
import com.payabli.sdk.taptopay.network.declined
import com.payabli.sdk.taptopay.network.fieldRefusal
import com.payabli.sdk.taptopay.network.rejected
import com.payabli.sdk.taptopay.provider.CardReadOutcome
import com.payabli.sdk.taptopay.provider.cardRead
import com.payabli.sdk.taptopay.session.MINTED_KEY
import com.payabli.sdk.taptopay.session.SessionFixture
import com.payabli.sdk.taptopay.session.TapToPayChargeActivity
import com.payabli.sdk.taptopay.session.TapToPayFailureReason
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import com.payabli.sdk.taptopay.session.completing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

private const val TRANS_ID = "12-abc"

private const val INITIATE = "/api/v2/MoneyIn/initiate"
private const val OLDER_SALE = "50.00"

/** A named payer, for the charges whose subject is not the customer. */
private val PAYER = TapToPayCustomerData(firstName = "Ada", lastName = "Payer", customerNumber = "cust-1")

private const val UPDATE = "/api/v2/MoneyIn/update/$TRANS_ID"

/** The service's wording when a close names a transaction the processor has no record of. */
private const val NOT_AT_PROCESSOR = "Unable to locate the transaction with the payment processor"

/** The reader's mark in the shared trace, which is how a second tap is counted. */
private const val READ = "reader:read"

/** A paypoint other than [ENTRY]. */
private const val ANOTHER_ENTRY = "another-entry-point"
private const val STRIPE_SHARING_ENTRY = "stripe-sharing-entry-5"

/** The idempotency check's 409, the one answer that is not a JSON object. */
private const val KEY_REFUSED = "Duplicated idempotencyKey"

/** Duplicate-payment protection's 409, which names the earlier sale. */
private const val DUPLICATE_SALE =
    """{"responseText":"Possible duplicate payment","isSuccess":false,"responseData":{"resultCode":3}}"""

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
    @org.junit.Before
    @org.junit.After
    fun forgetHeldKeys() = ChargeKeyStore.forgetHeld()

    private fun runnerOver(
        fixture: SessionFixture,
        environment: PayabliEnvironment = PayabliEnvironment.SANDBOX,
        contactlessEnabled: () -> Boolean = { true },
        entry: String = ENTRY,
    ) = TapToPayChargeRunner(
        entry = entry,
        environment = environment,
        coordinator = fixture.coordinator,
        manager = fixture.manager,
        reader = fixture.reader,
        contactlessEnabled = contactlessEnabled,
        client = TTPTransactionClient(fixture.enrollment.transport, fixture.enrollment.logger),
        store = fixture.enrollment.store,
        keys = fixture.keys,
    )

    private fun runnerGatedOnClose(
        fixture: SessionFixture,
        onClose: suspend () -> Unit,
    ) = TapToPayChargeRunner(
        entry = ENTRY,
        environment = PayabliEnvironment.SANDBOX,
        coordinator = fixture.coordinator,
        manager = fixture.manager,
        reader = fixture.reader,
        contactlessEnabled = { true },
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
                            .charge(details(), PAYER, TapToPayInvoiceData(), null)
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
    fun `a charge naming nobody is sent`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()

            val result =
                runCatching {
                    runnerOver(fixture).charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null)
                }

            assertEquals("${result.exceptionOrNull()}", TRANS_ID, result.getOrNull()?.paymentTransId)
            assertTrue("no payment was opened for a charge naming nobody", INITIATE in fixture.routes)
        }

    /** A ready session whose openings are all refused with [body] as a 400. */
    private suspend fun openingRefusedWith(body: String): SessionFixture =
        SessionFixture(
            RouteScript(
                RouteScript.CHALLENGE to listOf(challengeBody()),
                RouteScript.REGISTER to listOf(registerBody(status = "active")),
                RouteScript.ATTEST to listOf(attestBody()),
                RouteScript.CONFIG to listOf(configBody()),
                INITIATE to List(2) { body },
                statusFor = { if (it == INITIATE) 400 else 200 },
            ),
        ).also { it.coordinator.initialize() }

    @Test
    fun `a refused customer names the fields to collect, charges nothing and lets its attempt go`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = openingRefusedWith(fieldRefusal("Missing customer identifiers: customerNumber,email"))
            val runner = runnerOver(fixture)

            val failure =
                runCatching { runner.charge(details(), TapToPayCustomerData(), TapToPayInvoiceData(), null) }
                    .exceptionOrNull()

            assertTrue("$failure", failure is TapToPayException)
            failure as TapToPayException
            assertEquals(
                listOf("customerNumber", "billingEmail"),
                (failure.refusal as? TapToPayChargeRefusal.CustomerDataRefused)?.requiredFields,
            )
            assertEquals(PayabliErrorType.VALIDATION_ERROR, failure.type)
            assertEquals(TapToPayCapture.NOT_CHARGED, failure.capture)
            assertNull(failure.paymentTransId)
            assertNull("the reader was asked for a card", fixture.reader.lastReadRequest)

            runCatching {
                runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }
            assertEquals("the retry resent a refused opening's key", "$MINTED_KEY-2", fixture.keySent(1))
        }

    @Test
    fun `a refused customer naming no fields lists none`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = openingRefusedWith(fieldRefusal("Customer data is required"))

            val failure =
                runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
                    .exceptionOrNull() as TapToPayException

            assertEquals(
                emptyList<String>(),
                (failure.refusal as? TapToPayChargeRefusal.CustomerDataRefused)?.requiredFields,
            )
            assertEquals(PayabliErrorType.VALIDATION_ERROR, failure.type)
            assertEquals(TapToPayCapture.NOT_CHARGED, failure.capture)
        }

    @Test
    fun `the customer's code on another field is not a refused customer`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                openingRefusedWith(
                    fieldRefusal("Missing customer identifiers: customerNumber", field = "paymentDetails.totalAmount"),
                )

            val failure =
                runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
                    .exceptionOrNull() as TapToPayException

            assertEquals(PayabliErrorType.VALIDATION_ERROR, failure.type)
            assertNull(failure.refusal)
        }

    @Test
    fun `another code on the customer is not a refused customer`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                openingRefusedWith(fieldRefusal("Missing customer identifiers: customerNumber", code = "E5009"))

            val failure =
                runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
                    .exceptionOrNull() as TapToPayException

            assertEquals(PayabliErrorType.VALIDATION_ERROR, failure.type)
            assertNull(failure.refusal)
        }

    @Test
    fun `the customer's code on one of its fields is not a refused customer`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                openingRefusedWith(
                    fieldRefusal(
                        "customerData.firstName must not contain a card number",
                        field = "customerData.firstName",
                    ),
                )

            val failure =
                runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
                    .exceptionOrNull() as TapToPayException

            assertEquals(PayabliErrorType.VALIDATION_ERROR, failure.type)
            assertEquals(TapToPayCapture.NOT_CHARGED, failure.capture)
            assertNull(failure.refusal)
        }

    /** The resent key names an earlier opening, and refusing the retry's customer does not say whether that one charged. */
    @Test
    fun `a refused customer on a resent key stays unknown and keeps its key`() =
        runTest(timeout = TEST_TIMEOUT) {
            var openings = 0
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to
                            listOf(approved("{\"paymentTransId\":\"$TRANS_ID\"}")) +
                            List(2) { fieldRefusal("Missing customer identifiers: customerNumber") },
                        UPDATE to listOf("{}"),
                        statusFor = { path -> if (path == INITIATE && ++openings >= 2) 400 else 200 },
                    ),
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
                }.exceptionOrNull() as TapToPayException
            runCatching {
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }

            assertEquals(
                listOf("customerNumber"),
                (failure.refusal as? TapToPayChargeRefusal.CustomerDataRefused)?.requiredFields,
            )
            assertEquals("the earlier sale was reported as not charged", TapToPayCapture.UNKNOWN, failure.capture)
            assertEquals("the retry did not resend the held key", "$MINTED_KEY-1", fixture.keySent(1))
            assertEquals("the refusal let the held key go", "$MINTED_KEY-1", fixture.keySent(2))
        }

    @Test
    fun `a charge with the contactless radio off is refused as 3036 and sends nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Refused before anything is asked for, because the remedy is on the phone: told here, a
            // merchant turns the radio on and charges again; told after the tap, one tapped for nothing.
            val reported = mutableListOf<Pair<String, Map<String, String>>>()
            TelemetryRecorders.install { event, properties -> reported += event to properties }
            try {
                val fixture = readyFixture()

                val failure =
                    runCatching {
                        runnerOver(fixture, contactlessEnabled = { false })
                            .charge(details(), PAYER, TapToPayInvoiceData(), null)
                    }.exceptionOrNull() as TapToPayException

                assertEquals(PayabliErrorType.CONTACTLESS_DISABLED, failure.type)
                assertEquals(PayabliErrorType.CONTACTLESS_DISABLED.message, failure.reason)
                assertEquals(
                    "a charge with the radio off was reported as charged",
                    TapToPayCapture.NOT_CHARGED,
                    failure.capture,
                )
                assertNull("a payment was named with the radio off", failure.paymentTransId)
                assertFalse("a payment was opened with the radio off", INITIATE in fixture.routes)
                assertEquals("a key was minted with the radio off", 0, fixture.minted.get())
                assertEquals(
                    "the session was changed by a radio that is off",
                    TapToPaySessionState.Ready,
                    fixture.state,
                )
                assertTrue("the session stopped reporting ready", fixture.manager.isReady.value)
                assertEquals("the reader was asked for a card", null, fixture.reader.lastReadRequest)
                val charge = reported.single { it.first == TelemetryEvents.TTP_CHARGE_FAILED }.second
                assertEquals("3036", charge[TelemetryProperty.ERROR_NUMBER.key])
            } finally {
                TelemetryRecorders.clear()
            }
        }

    /** The retry describes the held sale, so the radio refusing it does not answer whether that sale was charged. */
    @Test
    fun `a retry of an unknown charge refused for the radio stays unknown and keeps its key`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture(updates = 2, opens = 2)
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            val openings = fixture.requests.count { it.path == INITIATE }

            val failure =
                runCatching {
                    runnerOver(fixture, contactlessEnabled = { false })
                        .charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
                }.exceptionOrNull() as TapToPayException
            val openingsAfterRefusal = fixture.requests.count { it.path == INITIATE }
            runCatching {
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }

            assertEquals(PayabliErrorType.CONTACTLESS_DISABLED, failure.type)
            assertEquals("the unknown sale was reported as not charged", TapToPayCapture.UNKNOWN, failure.capture)
            assertEquals("a payment was opened with the radio off", openings, openingsAfterRefusal)
            assertEquals("the held sale's key was lost", "$MINTED_KEY-1", fixture.keySent(1))
        }

    /** With nothing held, a retry is a sale of its own, so the radio refusing it charged nothing. */
    @Test
    fun `a retry refused for the radio with nothing held is not charged`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()

            val failure =
                runCatching {
                    runnerOver(fixture, contactlessEnabled = { false })
                        .charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
                }.exceptionOrNull() as TapToPayException

            assertEquals(PayabliErrorType.CONTACTLESS_DISABLED, failure.type)
            assertEquals(TapToPayCapture.NOT_CHARGED, failure.capture)
        }

    @Test
    fun `a charge with the radio on behaves as before`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The default answers on. An inverted read refuses every charge, so one full charge pins the
            // direction: nothing is refused while the radio reads on.
            val fixture = readyFixture()

            val receipt = runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)

            assertEquals(TRANS_ID, receipt.paymentTransId)
            assertEquals(listOf(INITIATE, UPDATE), fixture.routes.filter { it.startsWith("/api/v2/MoneyIn") })
            assertEquals(TapToPaySessionState.Ready, fixture.state)
        }

    @Test
    fun `a payment is opened, tapped and closed, in that order`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()

            val receipt =
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)

            assertEquals(TRANS_ID, receipt.paymentTransId)
            assertEquals(listOf(INITIATE, UPDATE), fixture.routes.filter { it.startsWith("/api/v2/MoneyIn") })
        }

    @Test
    fun `the reader is handed the identifier the payment was opened under`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Both fields, because the processor keys the reconciliation on them and this SDK has one
            // identifier for the two.
            val fixture = readyFixture()

            runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)

            assertEquals(TRANS_ID, fixture.reader.lastReadRequest?.merchantTransactionId)
            assertEquals(TRANS_ID, fixture.reader.lastReadRequest?.merchantOrderId)
        }

    @Test
    fun `a refused card is not reported as a completed payment`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The reader answers with a record for a refused card as readily as for an approved one, so
            // returning whatever it answered told a host a payment happened when none did.
            val fixture = readyFixture()
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.DECLINED, providerState = "DECLINED"),
            )

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            // Wrapped, because this branch names the payment on every failure after the opening. The
            // refusal is the cause, and the capture state is what a host reads before offering a retry.
            assertTrue(failure.toString(), failure is TapToPayException)
            val refused = failure as TapToPayException
            assertTrue(refused.cause.toString(), refused.cause is TTPTransactionException.CardRefused)
            assertEquals(PayabliErrorType.CARD_DECLINED, refused.type)
            assertEquals("a refused card was reported as charged", TapToPayCapture.NOT_CHARGED, refused.capture)
            assertEquals(TRANS_ID, refused.paymentTransId)
            assertTrue("the close was not sent for a refusal", UPDATE in fixture.routes)
        }

    @Test
    fun `a refused card settles its attempt, because a refusal is an answer`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture(updates = 2, opens = 2)
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.DECLINED, providerState = "DECLINED"),
            )

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            fixture.reader.answerReadWith(cardRead())
            runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)

            assertEquals("the next sale reused a settled attempt", "$MINTED_KEY-2", fixture.keySent(1))
        }

    @Test
    fun `an outcome that is neither an approval nor a refusal keeps its attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Nothing here says whether the money moved, so the key stays and a repeat is recognisable.
            val fixture = readyFixture(updates = 2, opens = 2)
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }

            assertTrue(failure.toString(), failure is TapToPayException)
            val unknown = failure as TapToPayException
            assertTrue(unknown.cause.toString(), unknown.cause is TTPTransactionException.OutcomeUnknown)
            assertEquals("an unknown outcome was reported as settled", TapToPayCapture.UNKNOWN, unknown.capture)
            assertEquals("the retry named a second attempt", "$MINTED_KEY-1", fixture.keySent(1))
        }

    @Test
    fun `an opening refused as a repeat of this SDK's own key keeps the attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The refusal is about the send, not the charge: the first opening reached the service and may
            // have left a transaction, so releasing the key here charges the card again on the next tap.
            var openings = 0
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to listOf(approved("{\"paymentTransId\":\"$TRANS_ID\"}"), KEY_REFUSED, KEY_REFUSED),
                        UPDATE to List(2) { "{}" },
                        statusFor = { path -> if (path == INITIATE && ++openings >= 2) 409 else 200 },
                    ),
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }

            assertEquals("a suppressed repeat released the attempt", "$MINTED_KEY-1", fixture.keySent(2))
        }

    /** A first opening refused as a duplicate answers the send, so the attempt is released. */
    @Test
    fun `an opening refused on a key this SDK has not resent releases the attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            var openings = 0
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to List(2) { approved("{\"paymentTransId\":\"$TRANS_ID\"}") },
                        UPDATE to listOf("{}"),
                        statusFor = { path -> if (path == INITIATE && ++openings == 1) 409 else 200 },
                    ),
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(cardRead())

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            assertEquals("a refused first opening kept its attempt", "$MINTED_KEY-2", fixture.keySent(1))
        }

    @Test
    fun `an opening refused on a resent key reports the payment as unknown`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The attempt this key names asked the reader, so the money may already have moved. Reporting
            // the retry's own state would tell a merchant it is safe to tap again.
            var openings = 0
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to listOf(approved("{\"paymentTransId\":\"$TRANS_ID\"}"), KEY_REFUSED),
                        UPDATE to listOf("{}"),
                        statusFor = { path -> if (path == INITIATE && ++openings >= 2) 409 else 200 },
                    ),
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            val failure =
                runCatching {
                    runnerOver(
                        fixture,
                    ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertEquals(
                "a refused repeat was reported as taking no money",
                TapToPayCapture.UNKNOWN,
                (failure as TapToPayException).capture,
            )
        }

    @Test
    fun `a key the service refused as a repeat is not resent past the bound`() =
        runTest(timeout = TEST_TIMEOUT) {
            val clock =
                java.util.concurrent.atomic
                    .AtomicLong(1_000_000_000L)
            var openings = 0
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to
                            listOf(
                                approved("{\"paymentTransId\":\"$TRANS_ID\"}"),
                                KEY_REFUSED,
                                approved("{\"paymentTransId\":\"$TRANS_ID\"}"),
                            ),
                        UPDATE to List(3) { "{}" },
                        statusFor = { path -> if (path == INITIATE && ++openings == 2) 409 else 200 },
                    ),
                    elapsedRealtimeNanos = clock::get,
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            // Half the bound before the refused resend and half after: a 409 does not restart the clock.
            clock.addAndGet(com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds / 2)
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }
            clock.addAndGet(com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds / 2)
            fixture.reader.answerReadWith(cardRead())
            runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)

            assertEquals(
                "an arrived key was resent after the service forgot it",
                "$MINTED_KEY-2",
                fixture.keySent(2),
            )
        }

    /** A resend that may have reached the service starts its two minutes again, so the key is held from then. */
    @Test
    fun `a resend that ends unknown restarts its key's clock`() =
        runTest(timeout = TEST_TIMEOUT) {
            val clock =
                java.util.concurrent.atomic
                    .AtomicLong(1_000_000_000L)
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to List(3) { approved("{\"paymentTransId\":\"$TRANS_ID\"}") },
                        UPDATE to List(3) { "{}" },
                    ),
                    elapsedRealtimeNanos = clock::get,
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )
            val half = com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds / 2

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            clock.addAndGet(half)
            runCatching {
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }
            clock.addAndGet(half)
            runCatching {
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }

            assertEquals("the resend did not restart the clock", "$MINTED_KEY-1", fixture.keySent(2))
        }

    /** A resend whose payment opened reached the service, so its key's clock restarts even if no card is read. */
    @Test
    fun `a resend refused once its payment opened restarts its key's clock`() =
        runTest(timeout = TEST_TIMEOUT) {
            val clock =
                java.util.concurrent.atomic
                    .AtomicLong(1_000_000_000L)
            var live = true
            var opens = 0
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to List(2) { configBody() },
                        INITIATE to List(3) { approved("{\"paymentTransId\":\"$TRANS_ID\"}") },
                        UPDATE to List(3) { "{}" },
                    ),
                    sendGate = { request -> if (request.path == INITIATE && ++opens == 2) live = false },
                    elapsedRealtimeNanos = clock::get,
                    isLive = { live },
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )
            val half = com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds / 2

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            clock.addAndGet(half)
            val refused =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
                }.exceptionOrNull()
            live = true
            clock.addAndGet(half)
            runCatching {
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }

            assertEquals(PayabliErrorType.SESSION_NOT_INITIALIZED, (refused as? TapToPayException)?.type)
            assertEquals("the refused resend did not restart the clock", "$MINTED_KEY-1", fixture.keySent(2))
        }

    /** A resend that failed before any connection wrote nothing at the service, so the key's clock stands. */
    @Test
    fun `a resend that never left the device keeps its key's earlier time`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A refused credential, a failure the network would leave unknown, and the caller withdrawing: none of
            // them reached the hand-off, so none restarts the clock.
            val failures =
                listOf(
                    PayabliGenericException(PayabliErrorType.TOKEN_PROVIDER_FAILED, "no token"),
                    PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "no token"),
                    CancellationException("the host withdrew"),
                )
            for (failure in failures) {
                ChargeKeyStore.forgetHeld()
                val clock =
                    java.util.concurrent.atomic
                        .AtomicLong(1_000_000_000L)
                var openings = 0
                val fixture =
                    SessionFixture(
                        RouteScript(
                            RouteScript.CHALLENGE to listOf(challengeBody()),
                            RouteScript.REGISTER to listOf(registerBody(status = "active")),
                            RouteScript.ATTEST to listOf(attestBody()),
                            RouteScript.CONFIG to listOf(configBody()),
                            INITIATE to List(2) { approved("{\"paymentTransId\":\"$TRANS_ID\"}") },
                            UPDATE to List(2) { "{}" },
                        ),
                        elapsedRealtimeNanos = clock::get,
                        unsent = { request -> failure.takeIf { request.path == INITIATE && ++openings == 2 } },
                    ).also { it.coordinator.initialize() }
                fixture.reader.answerReadWith(
                    cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
                )
                val half = com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds / 2

                runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
                clock.addAndGet(half)
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
                }
                clock.addAndGet(half)
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
                }

                assertEquals("$failure restarted the clock", "$MINTED_KEY-2", fixture.keySent(1))
            }
        }

    /** A resend answered 401 whose refresh then fails never left, so its key keeps its earlier time. */
    @Test
    fun `a resend refused 401 then failing its refresh keeps its key's earlier time`() =
        runTest(timeout = TEST_TIMEOUT) {
            val clock =
                java.util.concurrent.atomic
                    .AtomicLong(1_000_000_000L)
            var openings = 0
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to List(2) { approved("{\"paymentTransId\":\"$TRANS_ID\"}") },
                        UPDATE to List(2) { "{}" },
                    ),
                    elapsedRealtimeNanos = clock::get,
                    refused = { request ->
                        PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "the refresh did not reach the broker")
                            .takeIf { request.path == INITIATE && ++openings == 2 }
                    },
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))
            val half = com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds / 2

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            clock.addAndGet(half)
            runCatching {
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }
            clock.addAndGet(half)
            runCatching {
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }

            assertEquals("the refused resend restarted the clock", "$MINTED_KEY-2", fixture.keySent(1))
        }

    /** A resend lost after the hand-off may have written the marker, so its key's clock restarts from it. */
    @Test
    fun `a resend lost after the hand-off restarts its key's clock`() =
        runTest(timeout = TEST_TIMEOUT) {
            val clock =
                java.util.concurrent.atomic
                    .AtomicLong(1_000_000_000L)
            var openings = 0
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to List(3) { approved("{\"paymentTransId\":\"$TRANS_ID\"}") },
                        UPDATE to List(2) { "{}" },
                    ),
                    elapsedRealtimeNanos = clock::get,
                    lost = { request ->
                        PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "dropped")
                            .takeIf { request.path == INITIATE && ++openings == 2 }
                    },
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )
            val half = com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds / 2

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            clock.addAndGet(half)
            runCatching {
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }
            clock.addAndGet(half)
            runCatching {
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }

            assertEquals("the lost resend did not restart the clock", "$MINTED_KEY-1", fixture.keySent(2))
        }

    /** A declared retry of another sale is refused before it opens anything, and the held sale stays claimable. */
    @Test
    fun `a declared retry of a different sale is refused before it is sent`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture(updates = 2, opens = 2)
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))
            runCatching { runnerOver(fixture).charge(details("10.00"), PAYER, TapToPayInvoiceData(), null) }

            val refused =
                runCatching {
                    runnerOver(
                        fixture,
                    ).charge(details("20.00"), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
                }.exceptionOrNull() as? TapToPayException
            val openingsAfterRefusal = fixture.requests.count { it.path == INITIATE }
            runCatching {
                runnerOver(fixture).charge(details("10.00"), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }

            assertEquals(PayabliErrorType.RETRY_NOT_SAME_PAYMENT, refused?.type)
            assertEquals(TapToPayCapture.NOT_CHARGED, refused?.capture)
            assertEquals("the refused retry opened a sale", 1, openingsAfterRefusal)
            assertEquals("the held sale's key was lost", "$MINTED_KEY-1", fixture.keySent(1))
        }

    /** A sale whose opening failed before the card is not charged, so the open sale before it stays claimable. */
    @Test
    fun `an opening that fails before the card leaves the earlier unknown sale's key claimable`() =
        runTest(timeout = TEST_TIMEOUT) {
            var openings = 0
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to List(2) { approved("{\"paymentTransId\":\"$TRANS_ID\"}") },
                        UPDATE to List(2) { "{}" },
                    ),
                    unsent = { request ->
                        if (request.path == INITIATE && ++openings == 2) {
                            PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "dropped")
                        } else {
                            null
                        }
                    },
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )

            runCatching { runnerOver(fixture).charge(details("10.00"), PAYER, TapToPayInvoiceData(), null) }
            val failedOpening =
                runCatching {
                    runnerOver(fixture).charge(details("20.00"), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()
            runCatching {
                runnerOver(fixture).charge(details("10.00"), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }

            assertEquals(TapToPayCapture.NOT_CHARGED, (failedOpening as? TapToPayException)?.capture)
            assertEquals("the failed opening replaced the open sale's key", "$MINTED_KEY-1", fixture.keySent(1))
        }

    /** The host withdraws a new sale while it opens: nothing was charged, so the earlier sale's key stays held. */
    @Test
    fun `a sale withdrawn while it opens leaves the earlier unknown sale's key claimable`() =
        runTest(timeout = TEST_TIMEOUT) {
            var openings = 0
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to List(2) { approved("{\"paymentTransId\":\"$TRANS_ID\"}") },
                        UPDATE to List(2) { "{}" },
                    ),
                    unsent = { request ->
                        if (request.path == INITIATE && ++openings == 2) CancellationException("withdrawn") else null
                    },
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )

            runCatching { runnerOver(fixture).charge(details("10.00"), PAYER, TapToPayInvoiceData(), null) }
            val withdrawn =
                runCatching {
                    runnerOver(fixture).charge(details("20.00"), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()
            runCatching {
                runnerOver(fixture).charge(details("10.00"), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }

            assertTrue(withdrawn is CancellationException)
            assertEquals("the withdrawn sale replaced the open sale's key", "$MINTED_KEY-1", fixture.keySent(1))
        }

    /** A ready session whose [opening]-th charge opening, counting from 1, is answered 409 with [refusal]. */
    private suspend fun conflictOnOpening(
        opening: Int,
        refusal: String = KEY_REFUSED,
    ): SessionFixture {
        var openings = 0
        return SessionFixture(
            RouteScript(
                RouteScript.CHALLENGE to listOf(challengeBody()),
                RouteScript.REGISTER to listOf(registerBody(status = "active")),
                RouteScript.ATTEST to listOf(attestBody()),
                RouteScript.CONFIG to listOf(configBody()),
                INITIATE to
                    List(2) { if (it == opening - 1) refusal else approved("{\"paymentTransId\":\"$TRANS_ID\"}") },
                UPDATE to List(2) { "{}" },
                statusFor = { path -> if (path == INITIATE && ++openings == opening) 409 else 200 },
            ),
        ).also { it.coordinator.initialize() }
    }

    @Test
    fun `a charge refused as a repeat of an earlier one says the earlier charge has not finished`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = conflictOnOpening(2)
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            val failure =
                runCatching {
                    runnerOver(
                        fixture,
                    ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
                }.exceptionOrNull()

            assertEquals("$MINTED_KEY-1", fixture.keySent(1))
            assertEquals(PayabliErrorType.CHARGE_NOT_FINISHED, (failure as? TapToPayException)?.type)
        }

    @Test
    fun `a 409 on a key this SDK has not resent stays a conflict`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = conflictOnOpening(1)

            val failure =
                runCatching {
                    runnerOver(
                        fixture,
                    ).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertEquals(PayabliErrorType.CONFLICT, (failure as? TapToPayException)?.type)
        }

    @Test
    fun `a resend refused as a duplicate sale stays a conflict`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = conflictOnOpening(2, refusal = DUPLICATE_SALE)
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
                }.exceptionOrNull() as? TapToPayException

            assertEquals(PayabliErrorType.CONFLICT, failure?.type)
            assertEquals(TapToPayCapture.UNKNOWN, failure?.capture)
        }

    /** A first charge's time, a declared retry's time half the bound later, and how the retry failed. */
    private class AnsweredResend(
        val failure: TapToPayException?,
        val firstSentAt: Long,
        val resentAt: Long,
    )

    /**
     * Leaves a first charge unknown, then half the bound later sends a declared retry whose opening is answered
     * with [answer] and [status], or fails before the hand-off with [unsent], or after it with [lost].
     */
    private suspend fun resendAnswered(
        answer: String = approved("{\"paymentTransId\":\"$TRANS_ID\"}"),
        status: Int? = null,
        unsent: Exception? = null,
        lost: Exception? = null,
    ): AnsweredResend {
        val clock =
            java.util.concurrent.atomic
                .AtomicLong(1_000_000_000L)
        var answered = 0
        var unsentSends = 0
        var lostSends = 0
        val fixture =
            SessionFixture(
                RouteScript(
                    RouteScript.CHALLENGE to listOf(challengeBody()),
                    RouteScript.REGISTER to listOf(registerBody(status = "active")),
                    RouteScript.ATTEST to listOf(attestBody()),
                    RouteScript.CONFIG to listOf(configBody()),
                    INITIATE to listOf(approved("{\"paymentTransId\":\"$TRANS_ID\"}"), answer),
                    UPDATE to listOf("{}"),
                    statusFor = { path -> if (path == INITIATE && ++answered == 2) status else null },
                ),
                elapsedRealtimeNanos = clock::get,
                unsent = { request -> unsent.takeIf { request.path == INITIATE && ++unsentSends == 2 } },
                lost = { request -> lost.takeIf { request.path == INITIATE && ++lostSends == 2 } },
            ).also { it.coordinator.initialize() }
        fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))

        runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
        val firstSentAt = clock.get()
        val resentAt = clock.addAndGet(com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds / 2)
        val failure =
            runCatching {
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }.exceptionOrNull()
        return AnsweredResend(failure as? TapToPayException, firstSentAt, resentAt)
    }

    @Test
    fun `a resend refused as a repeat of its key keeps the key and its clock`() =
        runTest(timeout = TEST_TIMEOUT) {
            val resend = resendAnswered(KEY_REFUSED, status = 409)

            assertEquals(PayabliErrorType.CHARGE_NOT_FINISHED, resend.failure?.type)
            assertEquals(
                "the refused repeat restarted the clock",
                listOf(resend.firstSentAt),
                ChargeKeyStore.heldSince(),
            )
        }

    @Test
    fun `a resend refused as a duplicate sale keeps the key and restarts its clock`() =
        runTest(timeout = TEST_TIMEOUT) {
            val resend = resendAnswered(DUPLICATE_SALE, status = 409)

            assertEquals(PayabliErrorType.CONFLICT, resend.failure?.type)
            assertEquals(TapToPayCapture.UNKNOWN, resend.failure?.capture)
            assertEquals("the answered resend kept its clock", listOf(resend.resentAt), ChargeKeyStore.heldSince())
        }

    @Test
    fun `a resend refused as invalid keeps the key and restarts its clock`() =
        runTest(timeout = TEST_TIMEOUT) {
            val resend = resendAnswered("{}", status = 400)

            assertEquals(PayabliErrorType.VALIDATION_ERROR, resend.failure?.type)
            assertEquals("the answered resend kept its clock", listOf(resend.resentAt), ChargeKeyStore.heldSince())
        }

    @Test
    fun `a resend whose opening is declined keeps the key and restarts its clock`() =
        runTest(timeout = TEST_TIMEOUT) {
            val resend = resendAnswered(declined())

            assertEquals(PayabliErrorType.PAYMENT_NOT_OPENED, resend.failure?.type)
            assertEquals("the answered resend kept its clock", listOf(resend.resentAt), ChargeKeyStore.heldSince())
        }

    @Test
    fun `a resend lost after the hand-off keeps the key and restarts its clock`() =
        runTest(timeout = TEST_TIMEOUT) {
            val resend = resendAnswered(lost = PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "dropped"))

            assertEquals(TapToPayCapture.UNKNOWN, resend.failure?.capture)
            assertEquals("the lost resend kept its clock", listOf(resend.resentAt), ChargeKeyStore.heldSince())
        }

    @Test
    fun `a resend that never left keeps the key and its clock`() =
        runTest(timeout = TEST_TIMEOUT) {
            val resend = resendAnswered(unsent = PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "offline"))

            assertEquals(
                "the unsent resend restarted the clock",
                listOf(resend.firstSentAt),
                ChargeKeyStore.heldSince(),
            )
        }

    @Test
    fun `a card decline after a resent opening still reports unknown`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Initiate accepts the resent key past the service window: a new opening. The decline of that
            // opening must not claim the earlier attempt left no money, and must not settle the key the
            // earlier attempt still owns.
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to List(3) { approved("{\"paymentTransId\":\"$TRANS_ID\"}") },
                        UPDATE to List(3) { "{}" },
                    ),
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.DECLINED, providerState = "DECLINED"),
            )
            val failure =
                runCatching {
                    runnerOver(
                        fixture,
                    ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertEquals(
                "a decline on a resent opening was reported as taking no money",
                TapToPayCapture.UNKNOWN,
                (failure as TapToPayException).capture,
            )
            fixture.reader.answerReadWith(cardRead())
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }
            assertEquals(
                "a decline on a resent opening settled the earlier attempt",
                "$MINTED_KEY-1",
                fixture.keySent(2),
            )
        }

    @Test
    fun `an opening refused for any non-answer on a resent key keeps the attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A rate limit is refused before the idempotency check, so it says nothing about the opening
            // the earlier tap may have left behind.
            var openings = 0
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to List(3) { approved("{\"paymentTransId\":\"$TRANS_ID\"}") },
                        UPDATE to List(2) { "{}" },
                        statusFor = { path -> if (path == INITIATE && ++openings >= 2) 429 else 200 },
                    ),
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }

            assertEquals("a refused send released the attempt", "$MINTED_KEY-1", fixture.keySent(2))
        }

    @Test
    fun `a rate-limited opening reaches the host with the service's wait`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to listOf("{}"),
                        statusFor = { path -> if (path == INITIATE) 429 else 200 },
                        headersFor = { path -> if (path == INITIATE) mapOf("Retry-After" to "2") else emptyMap() },
                    ),
                ).also { it.coordinator.initialize() }

            val thrown =
                runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
                    .exceptionOrNull()

            assertTrue("expected a TapToPayException, got $thrown", thrown is TapToPayException)
            assertEquals(PayabliErrorType.RATE_LIMITED, (thrown as TapToPayException).type)
            assertEquals(2_000L, (thrown as PayabliRetryAfter).retryAfterMillis)
        }

    /** The paypoint answering unequipped settles a first opening, and says nothing about an earlier one. */
    @Test
    fun `an unequipped paypoint keeps the attempt when the key was resent`() =
        runTest(timeout = TEST_TIMEOUT) {
            var openings = 0
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to List(3) { approved("{\"paymentTransId\":\"$TRANS_ID\"}") },
                        UPDATE to List(2) { "{}" },
                        statusFor = { path -> if (path == INITIATE && ++openings >= 2) 404 else 200 },
                    ),
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }

            assertEquals("an unequipped answer released an earlier attempt", "$MINTED_KEY-1", fixture.keySent(2))
        }

    /**
     * A resent key reaches a controller only past the service's own marker, so what a rejection there
     * refuses is a fresh opening and the earlier attempt is exactly as unresolved.
     */
    @Test
    fun `a rejection on a resent key keeps the attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to
                            listOf(
                                approved("{\"paymentTransId\":\"$TRANS_ID\"}"),
                                rejected(),
                                approved("{\"paymentTransId\":\"$TRANS_ID\"}"),
                            ),
                        UPDATE to List(2) { "{}" },
                    ),
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }

            assertEquals("a rejection released the attempt", "$MINTED_KEY-1", fixture.keySent(2))
        }

    /** A first opening carries no earlier attempt, so a rejection of it answers everything there is. */
    @Test
    fun `a rejection on a key this SDK has not resent releases the attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to listOf(rejected(), approved("{\"paymentTransId\":\"$TRANS_ID\"}")),
                        UPDATE to listOf("{}"),
                    ),
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(cardRead())

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            assertEquals("a rejected first opening kept its attempt", "$MINTED_KEY-2", fixture.keySent(1))
        }

    /**
     * A decline past the marker means the service processed a *new* opening, having forgotten the key,
     * so it says no more about the earlier attempt than a rejection does.
     */
    @Test
    fun `a decline on a resent key keeps the attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to
                            listOf(
                                approved("{\"paymentTransId\":\"$TRANS_ID\"}"),
                                declined(),
                                approved("{\"paymentTransId\":\"$TRANS_ID\"}"),
                            ),
                        UPDATE to List(2) { "{}" },
                    ),
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }

            assertEquals("a decline kept the earlier attempt", "$MINTED_KEY-1", fixture.keySent(2))
        }

    @Test
    fun `a decline on a resent key reports the payment as unknown`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The attempt the key names asked the reader, so the money may have moved. Nothing this
            // opening was told is about that attempt, so there is nothing to resolve it with.
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to listOf(approved("{\"paymentTransId\":\"$TRANS_ID\"}"), declined()),
                        UPDATE to listOf("{}"),
                    ),
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            val failure =
                runCatching {
                    runnerOver(
                        fixture,
                    ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertEquals(
                "a decline on a resent key was reported as taking no money",
                TapToPayCapture.UNKNOWN,
                (failure as TapToPayException).capture,
            )
        }

    /** The service processed this opening and refused it, and there is no earlier attempt to confuse it with. */
    @Test
    fun `a decline on a key this SDK has not resent releases the attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to listOf(declined(), approved("{\"paymentTransId\":\"$TRANS_ID\"}")),
                        UPDATE to listOf("{}"),
                    ),
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(cardRead())

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            assertEquals("a declined first opening kept its attempt", "$MINTED_KEY-2", fixture.keySent(1))
        }

    @Test
    fun `a tap that never completed still closes the transaction`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Otherwise the opened payment is left standing at the paypoint with nothing to resolve it.
            val fixture = readyFixture()
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertTrue(failure.toString(), failure?.cause is CardReaderException.ReadFailed)
            assertTrue(UPDATE in fixture.routes)
            assertEquals(TapToPaySessionState.Ready, fixture.state)
        }

    @Test
    fun `a contactless-disabled read is 3036, and whether the card was charged stays unknown`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The vendor raises its own exception when the switch goes off while the reader waits. Left
            // unclassified it lands as 3018, which names no remedy. 3036 names it, turning the radio on, and
            // the capture still says the outcome is unknown: a card may have been presented first.
            val fixture = readyFixture()
            fixture.reader.failNextRead(CardReaderException.ContactlessDisabled(null))

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull() as TapToPayException

            assertEquals(PayabliErrorType.CONTACTLESS_DISABLED, failure.type)
            assertEquals("a mid-read failure was reported as settled", TapToPayCapture.UNKNOWN, failure.capture)
            assertEquals(TRANS_ID, failure.paymentTransId)
            assertTrue("the failed read was not closed", UPDATE in fixture.routes)
            assertEquals(
                TapToPaySessionState.Failed(TapToPayFailureReason.CONTACTLESS_DISABLED),
                fixture.state,
            )
            assertFalse("a failed session still reported ready", fixture.manager.isReady.value)
        }

    @Test
    fun `a JVM error during the tap still closes the transaction, and reaches the caller unchanged`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The vendor loads native code, so a LinkageError from the reader is the reachable shape here.
            // It is not an attestation outcome or a payment outcome and must not be converted into one, but
            // the transaction it leaves open is a real payment at the paypoint with nothing to resolve it.
            val fixture = readyFixture()
            val raised = LinkageError("libmc3.so")
            fixture.reader.failNextRead(raised)

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertSame("the error was converted rather than rethrown", raised, failure)
            assertTrue("the opened payment was left standing", UPDATE in fixture.routes)
        }

    @Test
    fun `a dead reader session expires the session so a repair can run`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()
            fixture.reader.failNextRead(CardReaderException.SessionUnusable(null))

            runCatching {
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
            }

            assertEquals(TapToPaySessionState.SessionExpired, fixture.state)
        }

    /**
     * A session that can be set up [builds] times, so a charge after a dead reader's expiry repairs it first.
     *
     * @param opens how many openings are answered.
     * @param noRecordFrom the first close, counted from zero, refused because the processor has no record of
     *   the payment; every close before it lands.
     * @param builds how many setups are answered.
     * @return the fixture, set up once.
     */
    private suspend fun rebuildableFixture(
        opens: Int = 2,
        noRecordFrom: Int = Int.MAX_VALUE,
        builds: Int = 3,
    ): SessionFixture {
        var closes = 0
        return SessionFixture(
            RouteScript(
                RouteScript.CHALLENGE to List(builds) { challengeBody() },
                RouteScript.REGISTER to List(builds) { registerBody(status = "active") },
                RouteScript.ATTEST to List(builds) { attestBody() },
                RouteScript.CONFIG to List(builds) { configBody() },
                INITIATE to List(opens) { approved("""{"paymentTransId":"$TRANS_ID"}""") },
                UPDATE to List(opens) { index -> if (index < noRecordFrom) "{}" else problemBody(NOT_AT_PROCESSOR) },
                statusFor = { if (it == UPDATE && closes++ >= noRecordFrom) 400 else 200 },
            ),
        ).also { it.coordinator.initialize() }
    }

    @Test
    fun `a reader gone before the read reports the session expired and nothing charged`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = rebuildableFixture(opens = 1)
            fixture.reader.failNextRead(CardReaderException.SessionUnusable(null))

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            val expired = failure as? TapToPayException
            assertEquals(failure.toString(), PayabliErrorType.READER_SESSION_EXPIRED, expired?.type)
            assertEquals(TapToPayCapture.NOT_CHARGED, expired?.capture)
            assertEquals(TRANS_ID, expired?.paymentTransId)
            assertTrue("the opened payment was closed", UPDATE in fixture.routes)
        }

    @Test
    fun `a reader gone before the read lets its attempt go`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = rebuildableFixture()
            fixture.reader.failNextRead(CardReaderException.SessionUnusable(null))
            val runner = runnerOver(fixture)

            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            fixture.reader.answerReadWith(cardRead())
            runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)

            assertEquals("$MINTED_KEY-1", fixture.keySent(0))
            assertEquals("the retry named a new attempt", "$MINTED_KEY-2", fixture.keySent(1))
        }

    @Test
    fun `a reader gone before the read stays session expired when the processor has no record of its close`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = rebuildableFixture(noRecordFrom = 0)
            fixture.reader.failNextRead(CardReaderException.SessionUnusable(null))
            val runner = runnerOver(fixture)

            val failure =
                runCatching {
                    runner.charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            val expired = failure as? TapToPayException
            assertEquals(failure.toString(), PayabliErrorType.READER_SESSION_EXPIRED, expired?.type)
            assertEquals(TapToPayCapture.NOT_CHARGED, expired?.capture)
            assertEquals(TapToPaySessionState.SessionExpired, fixture.state)
            val refusal = expired?.cause?.suppressed?.singleOrNull() as? PayabliValidationException
            assertEquals("the close's refusal rides on the expiry", NOT_AT_PROCESSOR, refusal?.detail)
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))
            runCatching {
                runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }
            assertEquals("the retry named a new attempt", "$MINTED_KEY-2", fixture.keySent(1))
        }

    @Test
    fun `a reader gone before the read reports nothing charged and lets its attempt go when its close fails`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A failing close is answered 500, which the close retries, so it costs three answers.
            var closes = 0
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to List(2) { challengeBody() },
                        RouteScript.REGISTER to List(2) { registerBody(status = "active") },
                        RouteScript.ATTEST to List(2) { attestBody() },
                        RouteScript.CONFIG to List(2) { configBody() },
                        INITIATE to List(2) { approved("""{"paymentTransId":"$TRANS_ID"}""") },
                        UPDATE to List(4) { problemBody("The service failed") },
                        statusFor = { if (it == UPDATE && closes++ < 3) 500 else 200 },
                    ),
                ).also { it.coordinator.initialize() }
            fixture.reader.failNextRead(CardReaderException.SessionUnusable(null))
            val runner = runnerOver(fixture)

            val failure =
                runCatching {
                    runner.charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            val expired = failure as? TapToPayException
            assertEquals(failure.toString(), PayabliErrorType.READER_SESSION_EXPIRED, expired?.type)
            assertEquals(TapToPayCapture.NOT_CHARGED, expired?.capture)
            assertEquals("every close was refused", 3, fixture.requestsTo(UPDATE).size)
            fixture.reader.answerReadWith(cardRead())
            runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            assertEquals("the retry named a new attempt", "$MINTED_KEY-2", fixture.keySent(1))
        }

    @Test
    fun `a reader gone before the read on a resent key keeps its outcome unknown and its attempt held`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = rebuildableFixture(opens = 3, noRecordFrom = 1)
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))
            val runner = runnerOver(fixture)
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            fixture.reader.failNextRead(CardReaderException.SessionUnusable(null))

            val failure =
                runCatching {
                    runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
                }.exceptionOrNull()

            assertEquals("the retry resent the held key", "$MINTED_KEY-1", fixture.keySent(1))
            val expired = failure as? TapToPayException
            assertEquals(failure.toString(), PayabliErrorType.READER_SESSION_EXPIRED, expired?.type)
            assertEquals(TapToPayCapture.UNKNOWN, expired?.capture)
            fixture.reader.answerReadWith(cardRead())
            runCatching {
                runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }
            assertEquals("the key is still held", "$MINTED_KEY-1", fixture.keySent(2))
        }

    /** The key one opening carried, from the request itself rather than from the store that held it. */
    private fun SessionFixture.keySent(attempt: Int = 0): String? =
        requestsTo(INITIATE)[attempt].headers["idempotencyKey"]

    @Test
    fun `a charge names its attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()

            runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)

            assertEquals("$MINTED_KEY-1", fixture.keySent())
        }

    @Test
    fun `a charge that settled leaves the next one to name its own attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Two sales, two attempts. Reusing the key here would refuse the second payment.
            val fixture = readyFixture(updates = 2, opens = 2)
            val runner = runnerOver(fixture)

            runner.charge(details(), PAYER, TapToPayInvoiceData(), null)
            runner.charge(details(), PAYER, TapToPayInvoiceData(), null)

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

            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry) }

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

            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry) }

            assertEquals("$MINTED_KEY-1", fixture.keySent(0))
            assertEquals(
                "the retry named a second attempt after the sale was captured",
                "$MINTED_KEY-1",
                fixture.keySent(1),
            )
        }

    @Test
    fun `a tap that failed keeps its attempt, because the close never says whether the sale captured`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A recorded close is not a recorded failure. The close makes the service pull the processor
            // and write back whatever it found, so a captured sale comes back captured; this SDK sends the
            // request and does not decode the answer, so it cannot tell that outcome from the ordinary one.
            // Releasing the key here would let the next charge take the money a second time.
            val fixture = readyFixture(updates = 2, opens = 2)
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))
            val runner = runnerOver(fixture)

            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)

            assertEquals("the retry named a second attempt", "$MINTED_KEY-1", fixture.keySent(1))
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

            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry) }

            assertEquals("$MINTED_KEY-1", fixture.keySent(0))
            assertEquals("the retry named a second attempt", "$MINTED_KEY-1", fixture.keySent(1))
        }

    /** A failed tap whose close is answered [updateStatus] with [updateBody], on a ready session. */
    private suspend fun failedTapClosedWith(
        updateStatus: Int,
        updateBody: String,
        opens: Int = 2,
    ): SessionFixture =
        SessionFixture(
            RouteScript(
                RouteScript.CHALLENGE to listOf(challengeBody()),
                RouteScript.REGISTER to listOf(registerBody(status = "active")),
                RouteScript.ATTEST to listOf(attestBody()),
                RouteScript.CONFIG to listOf(configBody()),
                INITIATE to List(opens) { approved("""{"paymentTransId":"$TRANS_ID"}""") },
                UPDATE to List(9) { updateBody },
                statusFor = { if (it == UPDATE) updateStatus else 200 },
            ),
        ).also { it.coordinator.initialize() }

    /**
     * A failed-tap script whose first close lands and every later one is refused as unknown to the
     * processor. A close that lands releases nothing, so the first charge's key stays held: the setup
     * a resent run needs.
     */
    private suspend fun firstCloseLandsThenNoRecord(opens: Int = 3): SessionFixture {
        var closes = 0
        return SessionFixture(
            RouteScript(
                RouteScript.CHALLENGE to listOf(challengeBody()),
                RouteScript.REGISTER to listOf(registerBody(status = "active")),
                RouteScript.ATTEST to listOf(attestBody()),
                RouteScript.CONFIG to listOf(configBody()),
                INITIATE to List(opens) { approved("""{"paymentTransId":"$TRANS_ID"}""") },
                UPDATE to List(opens) { index -> if (index == 0) "{}" else problemBody(NOT_AT_PROCESSOR) },
                statusFor = { if (it == UPDATE && ++closes > 1) 400 else 200 },
            ),
        ).also { it.coordinator.initialize() }
    }

    private fun problemBody(detail: String) =
        """{"type":"https://docs.payabli.com/","title":"Bad Request","status":400,"detail":"$detail","code":"400","errors":{}}"""

    @Test
    fun `a failed tap the processor never saw settles its attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = failedTapClosedWith(400, problemBody(NOT_AT_PROCESSOR))
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))
            val runner = runnerOver(fixture)

            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }

            assertEquals("$MINTED_KEY-1", fixture.keySent(0))
            assertEquals("the next charge resent a settled attempt", "$MINTED_KEY-2", fixture.keySent(1))
        }

    @Test
    fun `an indeterminate tap the processor has no record of keeps its attempt held`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = failedTapClosedWith(400, problemBody(NOT_AT_PROCESSOR))
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))
            val runner = runnerOver(fixture)

            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching {
                runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }

            assertEquals("$MINTED_KEY-1", fixture.keySent(0))
            assertEquals("the retry resent the held key", "$MINTED_KEY-1", fixture.keySent(1))
        }

    @Test
    fun `a failed tap whose close is refused for any other reason keeps its attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = failedTapClosedWith(400, problemBody("The request was not valid"))
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))
            val runner = runnerOver(fixture)

            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry) }

            assertEquals("the retry named a second attempt", "$MINTED_KEY-1", fixture.keySent(1))
        }

    @Test
    fun `a resent attempt the processor never saw stays unsettled, because it names an earlier opening`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = firstCloseLandsThenNoRecord(opens = 3)
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))
            val runner = runnerOver(fixture)

            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry) }
            fixture.reader.answerReadWith(cardRead())
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry) }

            assertEquals("$MINTED_KEY-1", fixture.keySent(1))
            assertEquals("the earlier opening's key was let go", "$MINTED_KEY-1", fixture.keySent(2))
        }

    @Test
    fun `a failed tap the processor has no record of says the payment was not taken`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = failedTapClosedWith(400, problemBody(NOT_AT_PROCESSOR))
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            val notTaken = failure as TapToPayException
            assertEquals(PayabliErrorType.PAYMENT_NOT_TAKEN, notTaken.type)
            assertEquals(TapToPayCapture.NOT_CHARGED, notTaken.capture)
            assertEquals(TRANS_ID, notTaken.paymentTransId)
            assertEquals(NOT_AT_PROCESSOR, notTaken.detail)
        }

    @Test
    fun `a failed tap whose close is refused for any other reason stays tap not completed`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = failedTapClosedWith(400, problemBody("The request was not valid"))
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            val tapFailure = failure as? TapToPayException
            assertEquals(PayabliErrorType.TAP_NOT_COMPLETED, tapFailure?.type)
            assertEquals(TapToPayCapture.UNKNOWN, tapFailure?.capture)
        }

    @Test
    fun `an indeterminate tap whose close the processor has no record of says the payment was not closed`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = failedTapClosedWith(400, problemBody(NOT_AT_PROCESSOR))
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            val notClosed = failure as TapToPayException
            assertEquals(PayabliErrorType.PAYMENT_NOT_CLOSED, notClosed.type)
            assertEquals(TapToPayCapture.UNKNOWN, notClosed.capture)
            assertEquals(TRANS_ID, notClosed.paymentTransId)
            assertEquals(NOT_AT_PROCESSOR, notClosed.detail)
        }

    @Test
    fun `an approved tap whose close the processor has no record of is still charged`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = failedTapClosedWith(400, problemBody(NOT_AT_PROCESSOR))
            fixture.reader.answerReadWith(cardRead())

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            val tapFailure = failure as? TapToPayException
            assertEquals(PayabliErrorType.PAYMENT_NOT_CLOSED, tapFailure?.type)
            assertEquals(TapToPayCapture.CHARGED, tapFailure?.capture)
            assertEquals(TRANS_ID, tapFailure?.paymentTransId)
        }

    @Test
    fun `a failed tap on a key this SDK resent keeps tap not completed whatever the close answers`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = firstCloseLandsThenNoRecord(opens = 3)
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))

            val failure =
                runCatching {
                    runnerOver(
                        fixture,
                    ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
                }.exceptionOrNull()

            assertEquals("the retry resent the held key", "$MINTED_KEY-1", fixture.keySent(1))
            val tapFailure = failure as? TapToPayException
            assertEquals(PayabliErrorType.TAP_NOT_COMPLETED, tapFailure?.type)
            assertEquals(TapToPayCapture.UNKNOWN, tapFailure?.capture)
            assertEquals(TRANS_ID, tapFailure?.paymentTransId)
            fixture.reader.answerReadWith(cardRead())
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }
            assertEquals("the key is still held", "$MINTED_KEY-1", fixture.keySent(2))
        }

    @Test
    fun `an indeterminate tap on a key this SDK resent keeps payment not closed when the processor has no record`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = firstCloseLandsThenNoRecord(opens = 3)
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            val failure =
                runCatching {
                    runnerOver(
                        fixture,
                    ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
                }.exceptionOrNull()

            assertEquals("the retry resent the held key", "$MINTED_KEY-1", fixture.keySent(1))
            val tapFailure = failure as? TapToPayException
            assertEquals(PayabliErrorType.PAYMENT_NOT_CLOSED, tapFailure?.type)
            assertEquals(TapToPayCapture.UNKNOWN, tapFailure?.capture)
            assertEquals(TRANS_ID, tapFailure?.paymentTransId)
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }
            assertEquals("the key is still held", "$MINTED_KEY-1", fixture.keySent(2))
        }

    @Test
    fun `a JVM error during the tap keeps its place when the close says the processor has no record`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The vendor loads native code, so a LinkageError from the reader is the reachable shape here. The
            // close's refusal naming a payment the processor holds no record of is about the charge, but an
            // `Error` is the caller's to see: the refusal rides suppressed, never in the error's place.
            val fixture = failedTapClosedWith(400, problemBody(NOT_AT_PROCESSOR))
            val raised = LinkageError("libmc3.so")
            fixture.reader.failNextRead(raised)

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertSame("the error was converted rather than rethrown", raised, failure)
            val suppressed = failure?.suppressed.orEmpty()
            assertTrue(
                "the close's refusal was dropped: ${suppressed.toList()}",
                suppressed.singleOrNull() is PayabliException,
            )
            assertTrue("the opened payment was left standing", UPDATE in fixture.routes)
        }

    @Test
    fun `a second terminal for one entry point repeats the attempt the first left unsettled`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A terminal is built per call and caches nothing, so the retry usually comes from a second one
            // after the screen holding the first was rebuilt. A key on the instance would be gone.
            val fixture = readyFixture(updates = 0, opens = 2)

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching {
                runnerOver(
                    fixture,
                ).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)
            }

            assertEquals("$MINTED_KEY-1", fixture.keySent(0))
            assertEquals("the second terminal named its own attempt", "$MINTED_KEY-1", fixture.keySent(1))
        }

    /** A sale of its own after one whose outcome is unknown is a second sale, so it sends a key of its own. */
    @Test
    fun `an ordinary charge after an unsettled one sends a fresh key`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture(updates = 0, opens = 2)

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            assertEquals("$MINTED_KEY-1", fixture.keySent(0))
            assertEquals("an ordinary charge took the held key", "$MINTED_KEY-2", fixture.keySent(1))
        }

    /** The ordinary sale in between is held as the newest, so the retry that follows it is of that sale. */
    @Test
    fun `a declared retry after two unsettled charges repeats the later one`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture(updates = 0, opens = 3)

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            runCatching {
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }

            assertEquals("$MINTED_KEY-2", fixture.keySent(2))
        }

    /** Each unknown charge leaves only its own key, so an entry point never fills the store by itself. */
    @Test
    fun `four unknown charges on one entry point leave room for a fifth`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture(updates = 0, opens = ChargeKeyStore.MAX + 1)

            repeat(ChargeKeyStore.MAX) {
                runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            }
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            assertEquals("$MINTED_KEY-${ChargeKeyStore.MAX + 1}", fixture.keySent(ChargeKeyStore.MAX))
        }

    /** A retry is of the newest unknown charge, so the one before it is let go rather than held to take room. */
    @Test
    fun `a charge that ends unknown drops the older unknown charge's key`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture(updates = 2, opens = 2)
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))
            repeat(2) { runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) } }

            fixture.keys.settle(ENTRY, PayabliEnvironment.SANDBOX, "$MINTED_KEY-2")
            val next = fixture.keys.reserve(ENTRY, PayabliEnvironment.SANDBOX, PayabliAttempt.Retry)

            assertEquals("the older unknown charge's key was kept", "$MINTED_KEY-3", next.key)
        }

    /** A sale that settles answers only itself, so the unknown one before it can still be retried. */
    @Test
    fun `a newer charge that settles leaves the older unknown one claimable`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture(updates = 3, opens = 3)
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            fixture.reader.answerReadWith(cardRead())
            runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)

            runCatching {
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }

            assertEquals("$MINTED_KEY-1", fixture.keySent(2))
        }

    /** The terminal a host holds passes the declaration on, so a retry declared there repeats the held sale. */
    @Test
    fun `a retry declared on the terminal repeats the unsettled charge`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture(updates = 0, opens = 2)
            val terminal = { PayabliTTP.over(fixture.coordinator, runnerOver(fixture)) }

            runCatching { terminal().charge(details(), PAYER) }
            runCatching { terminal().charge(details(), PAYER, attempt = PayabliAttempt.Retry) }

            assertEquals("$MINTED_KEY-1", fixture.keySent(1))
        }

    @Test
    fun `an amount of zero opens nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()

            val failure =
                runCatching {
                    runnerOver(fixture)
                        .charge(details("0.00"), PAYER, TapToPayInvoiceData(), null)
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
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
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
                        .charge(details("0.001"), PAYER, TapToPayInvoiceData(), null)
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
                        PAYER,
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
                        PAYER,
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
                .charge(details("12.345"), PAYER, TapToPayInvoiceData(), null)

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
                        PAYER,
                        TapToPayInvoiceData(),
                        null,
                    )
                }.exceptionOrNull()
            val unsendable =
                runCatching {
                    runnerOver(fixture).charge(
                        TapToPayPaymentDetails(BigDecimal("10.00"), serviceFee = BigDecimal("1E+2147483647")),
                        PAYER,
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
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertTrue(failure.toString(), failure?.cause is IllegalStateException)
            assertEquals(PayabliErrorType.DEVICE_SETUP_REQUIRED, (failure as TapToPayException).type)
            assertEquals(
                TapToPaySessionState.Failed(TapToPayFailureReason.DEVICE_SETUP_REQUIRED),
                fixture.state,
            )
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
            var outcome: Result<TapToPayResult>? = null
            charging =
                launch {
                    outcome =
                        runCatching {
                            runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                        }
                }
            charging.join()

            // The service was told about a card the processor has already taken, because the alternative is
            // a charge nothing can reconcile.
            assertTrue(fixture.routes.toString(), UPDATE in fixture.routes)
            // And the caller is handed the payment, which is what `PayabliTTP.charge` promises. A
            // `CancellationException` states the charge did not happen, and by this line it has: the close
            // landed and the attempt is settled. Throwing here would leave a withdrawing caller with no
            // identifier for a payment the customer was charged for, which is the one outcome in this window
            // that cannot be reconciled from the app.
            //
            // `withContext(NonCancellable)` does not rethrow on exit, so this is what the code already does.
            // Measured on three shapes - the test dispatcher, `Dispatchers.IO`, and a job cancelled before
            // the block was entered - and all three returned. It is asserted here so that stays a decision.
            assertEquals(TRANS_ID, outcome?.getOrNull()?.paymentTransId)
        }

    @Test
    fun `a terminal that was never set up opens nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = SessionFixture(script())

            runCatching {
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
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
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertEquals(TRANS_ID, (failure as TapToPayException).paymentTransId)
            assertEquals(
                "a tap that ended without an answer may still have taken the money",
                TapToPayCapture.UNKNOWN,
                failure.capture,
            )
        }

    @Test
    fun `a failure before the payment is opened names none`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A payment that was never opened must not be reported as one, or a caller reconciles a
            // transaction that does not exist.
            val fixture = SessionFixture(script())

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertNull((failure as TapToPayException).paymentTransId)
            assertEquals(TapToPayCapture.NOT_CHARGED, failure.capture)
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
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertEquals(TRANS_ID, (failure as TapToPayException).paymentTransId)
            assertEquals(
                "the money moved and the failure did not say so",
                TapToPayCapture.CHARGED,
                failure.capture,
            )
        }

    @Test
    fun `a close that failed reaches the caller and telemetry as the payment not closed`() =
        runTest(timeout = TEST_TIMEOUT) {
            val reported = mutableListOf<Pair<String, Map<String, String>>>()
            TelemetryRecorders.install { event, properties -> reported += event to properties }
            try {
                val fixture =
                    SessionFixture(scriptWithCloseControl(closes = 3) { true })
                        .also { it.coordinator.initialize() }

                val failure =
                    runCatching {
                        runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                    }.exceptionOrNull() as TapToPayException

                assertEquals(PayabliErrorType.PAYMENT_NOT_CLOSED, failure.type)
                val charge = reported.single { it.first == TelemetryEvents.TTP_CHARGE_FAILED }.second
                assertEquals("3022", charge[TelemetryProperty.ERROR_NUMBER.key])
            } finally {
                TelemetryRecorders.clear()
            }
        }

    @Test
    fun `a failure claiming nothing was sent, after the card was asked for, is an unconfirmed outcome`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Its type says nothing was sent, which is not true once the reader may have taken the card.
            val fixture = readyFixture(updates = 2)
            fixture.reader.failNextRead(IllegalStateException("the vendor's own state check"))

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull() as TapToPayException

            assertEquals(PayabliErrorType.PAYMENT_OUTCOME_UNKNOWN, failure.type)
        }

    @Test
    fun `a refused card whose close failed is still reported as refused`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The close records the outcome and changes nothing about it. A host told the transport failed
            // retries a card that was declined.
            val reported = mutableListOf<Pair<String, Map<String, String>>>()
            TelemetryRecorders.install { event, properties -> reported += event to properties }
            try {
                val fixture =
                    SessionFixture(scriptWithCloseControl(closes = 3) { true })
                        .also { it.coordinator.initialize() }
                fixture.reader.answerReadWith(
                    cardRead(outcome = CardReadOutcome.DECLINED, providerState = "DECLINED"),
                )

                val failure =
                    runCatching {
                        runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                    }.exceptionOrNull()

                assertTrue(failure.toString(), failure is TapToPayException)
                val refused = failure as TapToPayException
                assertTrue(refused.cause.toString(), refused.cause is TTPTransactionException.CardRefused)
                assertEquals(PayabliErrorType.CARD_DECLINED, refused.type)
                assertEquals(TapToPayCapture.NOT_CHARGED, refused.capture)
                assertEquals(TRANS_ID, refused.paymentTransId)
                val charge = reported.single { it.first == TelemetryEvents.TTP_CHARGE_FAILED }.second
                assertEquals("3020", charge[TelemetryProperty.ERROR_NUMBER.key])
            } finally {
                TelemetryRecorders.clear()
            }
        }

    @Test
    fun `a refused card whose close failed still says the close failed, and can be closed again`() =
        runTest(timeout = TEST_TIMEOUT) {
            var closeFails = true
            val fixture =
                SessionFixture(scriptWithCloseControl(closes = 4) { closeFails })
                    .also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.DECLINED, providerState = "DECLINED"),
            )
            val runner = runnerOver(fixture)

            val failure =
                runCatching {
                    runner.charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull() as TapToPayException

            val suppressed = failure.cause?.suppressed.orEmpty()
            assertTrue(
                "the close failure was dropped: ${suppressed.toList()}",
                suppressed.singleOrNull() is PayabliException,
            )

            closeFails = false
            runner.closeCaptured(TRANS_ID)
        }

    @Test
    fun `a refused card whose close failed lets its attempt go, so the next card opens its own`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A refusal is definite whether or not the close landed. Holding the key sends the next card
            // under the refused attempt, and a refusal on a resent key can only report unknown.
            var closeFails = true
            val fixture =
                SessionFixture(scriptWithCloseControl(opens = 2, closes = 4) { closeFails })
                    .also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.DECLINED, providerState = "DECLINED"),
            )
            val runner = runnerOver(fixture)

            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            closeFails = false
            val next =
                runCatching {
                    runner.charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull() as TapToPayException

            assertEquals("the next card reused the refused attempt", "$MINTED_KEY-2", fixture.keySent(1))
            assertEquals(TapToPayCapture.NOT_CHARGED, next.capture)
        }

    @Test
    fun `a refused card on a resent key whose close failed keeps the earlier attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            var closeFails = false
            val fixture =
                SessionFixture(scriptWithCloseControl(opens = 3, closes = 5) { closeFails })
                    .also { it.coordinator.initialize() }
            val runner = runnerOver(fixture)
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }

            closeFails = true
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.DECLINED, providerState = "DECLINED"),
            )
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry) }

            closeFails = false
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry) }

            assertEquals("a refusal settled the earlier attempt", "$MINTED_KEY-1", fixture.keySent(2))
        }

    @Test
    fun `an unknown outcome whose close failed reports the close failure`() =
        runTest(timeout = TEST_TIMEOUT) {
            // No known answer to protect, so the close failure is the more specific thing to say.
            val fixture =
                SessionFixture(scriptWithCloseControl(closes = 3) { true })
                    .also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )

            val failure =
                runCatching {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            val unknown = failure as TapToPayException
            assertTrue(unknown.cause.toString(), unknown.cause is PayabliException)
            assertEquals(TapToPayCapture.UNKNOWN, unknown.capture)
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
                    runner.charge(details(), PAYER, TapToPayInvoiceData(), null)
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
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }

            closeFails = false
            withdrawOnClose = true
            val withdrawn = runCatching { runner.closeCaptured(TRANS_ID) }.exceptionOrNull()

            assertTrue(withdrawn.toString(), withdrawn is CancellationException)
        }

    @Test
    fun `a withdrawn recovery still finishes the close it started`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The window this guards is the recovery's uncancellable step: once the close is in flight the
            // settle and the drop have to land with it, or a withdrawn caller leaves a payment that closed
            // still held and an attempt still named. Cancelling from the close's own gate puts the job in
            // that state deterministically rather than racing a timer against it.
            var closeFails = true
            var cancelOnClose = false
            var recovering: Job? = null
            val fixture =
                SessionFixture(scriptWithCloseControl(closes = 6) { closeFails })
                    .also { it.coordinator.initialize() }
            val runner =
                runnerGatedOnClose(fixture) {
                    if (cancelOnClose) recovering?.cancel()
                }
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            val closesBefore = fixture.routes.count { it.startsWith("/api/v2/MoneyIn/update") }

            closeFails = false
            cancelOnClose = true
            // A child job, so the cancellation lands on the recovery rather than on the test itself.
            recovering = launch { runCatching { runner.closeCaptured(TRANS_ID) } }
            recovering.join()

            // The close went out despite the withdrawal.
            assertTrue(
                fixture.routes.toString(),
                fixture.routes.count { it.startsWith("/api/v2/MoneyIn/update") } > closesBefore,
            )
            // And the payment is no longer held, which is the half a cancellation would otherwise skip:
            // asking again is refused rather than closing a payment that already closed.
            cancelOnClose = false
            val again = runCatching { runner.closeCaptured(TRANS_ID) }.exceptionOrNull()
            assertTrue(again.toString(), again is TapToPayException)
            assertEquals(TapToPayCapture.UNKNOWN, (again as TapToPayException).capture)
        }

    @Test
    fun `a recovery that failed leaves the payment held for another attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Three for the charge's own close, three for the recovery that also gives up, one for the
            // recovery that lands. Clearing the held payment anywhere but after a close that succeeded
            // would make recovery one-shot and leave this the only test that says so.
            var closeFails = true
            val fixture =
                SessionFixture(scriptWithCloseControl(closes = 7) { closeFails })
                    .also { it.coordinator.initialize() }
            val runner = runnerOver(fixture)
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }

            val firstRetry = runCatching { runner.closeCaptured(TRANS_ID) }.exceptionOrNull()
            assertTrue(firstRetry.toString(), firstRetry is TapToPayException)
            assertEquals(
                "a failed recovery stopped saying the card was charged",
                TapToPayCapture.CHARGED,
                (firstRetry as TapToPayException).capture,
            )

            closeFails = false
            runner.closeCaptured(TRANS_ID)

            assertEquals("the card was read again", 1, fixture.enrollment.trace.count { it == READ })
            assertEquals("a second payment was opened", 1, fixture.routes.count { it == INITIATE })
        }

    @Test
    fun `a recovery that closed lets the attempt go, so the next charge opens its own`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The close resolves the transaction whenever it lands, so the attempt is over then too. Holding
            // the key past it makes the next charge send one that names a payment already resolved.
            var closeFails = true
            val fixture =
                SessionFixture(scriptWithCloseControl(opens = 2, closes = 5) { closeFails })
                    .also { it.coordinator.initialize() }
            val runner = runnerOver(fixture)
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }

            closeFails = false
            runner.closeCaptured(TRANS_ID)
            runner.charge(details(), PAYER, TapToPayInvoiceData(), null)

            assertEquals("the next charge reused the closed attempt", "$MINTED_KEY-2", fixture.keySent(1))
        }

    @Test
    fun `a recovery for an outcome that was never definite keeps its attempt`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The held payment is kept for every outcome, because the transaction is open at the service
            // whatever the card did. Settling on a recovery for an indeterminate read drops the only handle
            // on an attempt that may have taken money, and the next charge mints a fresh key.
            var closeFails = true
            val fixture =
                SessionFixture(scriptWithCloseControl(opens = 2, closes = 6) { closeFails })
                    .also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )
            val runner = runnerOver(fixture)

            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            closeFails = false
            runner.closeCaptured(TRANS_ID)

            fixture.reader.answerReadWith(cardRead())
            runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry)

            // The same key both times, rather than a literal: a kept attempt is one the retry names again.
            assertEquals(
                "the recovery settled an attempt that was never answered",
                fixture.keySent(0),
                fixture.keySent(1),
            )
        }

    @Test
    fun `a recovery that fails reports the answer the reader gave, not a charge`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A refused card leaves a transaction to close, so the recovery is reachable for one. Reporting
            // it as charged tells a host money moved when the card was turned down.
            val fixture =
                SessionFixture(scriptWithCloseControl(opens = 1, closes = 3) { true })
                    .also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.DECLINED, providerState = "DECLINED"),
            )
            val runner = runnerOver(fixture)

            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            val failure = runCatching { runner.closeCaptured(TRANS_ID) }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertEquals(
                "a refused card was reported as charged by the recovery",
                TapToPayCapture.NOT_CHARGED,
                (failure as TapToPayException).capture,
            )
        }

    @Test
    fun `a recovery that fails on a resent decline still reports unknown`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Same card answer as the fresh-key recovery, but the key names an earlier attempt. NOT_CHARGED
            // would tell a host another charge is safe while that attempt is unresolved.
            var closeFails = false
            val fixture =
                SessionFixture(scriptWithCloseControl(opens = 2, closes = 5) { closeFails })
                    .also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"),
            )
            val runner = runnerOver(fixture)

            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            closeFails = true
            fixture.reader.answerReadWith(
                cardRead(outcome = CardReadOutcome.DECLINED, providerState = "DECLINED"),
            )
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null, attempt = PayabliAttempt.Retry) }
            val failure = runCatching { runner.closeCaptured(TRANS_ID) }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertEquals(
                "a decline on a resent opening was reported as taking no money by the recovery",
                TapToPayCapture.UNKNOWN,
                (failure as TapToPayException).capture,
            )
        }

    @Test
    fun `closing a payment this terminal does not hold says unknown, never not-charged`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The state a host reaches after a restart, holding an identifier it persisted. Reporting it as
            // never charged would tell that host a second charge is safe for a payment that took money.
            val fixture = readyFixture()
            val runner = runnerOver(fixture)

            val refusal =
                runCatching { runner.closeCaptured("12-not-held") }.exceptionOrNull()

            assertTrue(refusal.toString(), refusal is TapToPayException)
            assertEquals("12-not-held", (refusal as TapToPayException).paymentTransId)
            assertEquals(TapToPayCapture.UNKNOWN, refusal.capture)
        }

    /**
     * Charges with [runner] while its entry point is held, and returns what it was refused with.
     *
     * The charge is asserted finished before anything is released, so a charge that waits fails here.
     *
     * @param runner the terminal the second charge is made on.
     * @param attempt whether the second charge is declared a retry.
     * @param payment the sale the second charge describes.
     * @return the refusal the second charge ended with.
     */
    private suspend fun TestScope.refusedAtOnce(
        runner: TapToPayChargeRunner,
        attempt: PayabliAttempt = PayabliAttempt.NewPayment,
        payment: TapToPayPaymentDetails = details(),
    ): TapToPayException {
        val second =
            async(UnconfinedTestDispatcher(testScheduler)) {
                runCatching { runner.charge(payment, PAYER, TapToPayInvoiceData(), null, attempt) }
            }
        assertTrue("the second charge waited for the first", second.isCompleted)
        return second.await().exceptionOrNull() as TapToPayException
    }

    @Test
    fun `a second terminal's charge on an entry point is refused at once while the first reads a card`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(script(), readGate = { held.await() }).also { it.coordinator.initialize() }
            val first = chargeParkedInTheRead(fixture, held)
            val reserved = fixture.minted.get()

            val refusal = refusedAtOnce(runnerOver(fixture))

            assertEquals(PayabliErrorType.TERMINAL_NOT_READY, refusal.type)
            assertEquals(TapToPayCapture.NOT_CHARGED, refusal.capture)
            assertEquals("the refused charge reserved a key", reserved, fixture.minted.get())
            assertEquals("the refused charge sent an opening", 1, fixture.requestsTo(INITIATE).size)
            assertEquals("the refusal moved the session", waiting, fixture.state)
            held.complete(Unit)
            assertEquals(TRANS_ID, completing("the first charge") { first.await() }.getOrThrow().paymentTransId)
        }

    @Test
    fun `a second charge on the same terminal is refused at once while the first reads a card`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(script(), readGate = { held.await() }).also { it.coordinator.initialize() }
            val terminal = runnerOver(fixture)
            val first = chargeParkedInTheRead(fixture, held, terminal)
            val reserved = fixture.minted.get()

            val refusal = refusedAtOnce(terminal)

            assertEquals(PayabliErrorType.TERMINAL_NOT_READY, refusal.type)
            assertEquals("the refused charge reserved a key", reserved, fixture.minted.get())
            assertEquals("the refused charge sent an opening", 1, fixture.requestsTo(INITIATE).size)
            held.complete(Unit)
            assertEquals(TRANS_ID, completing("the first charge") { first.await() }.getOrThrow().paymentTransId)
        }

    @Test
    fun `a terminal on another environment for the entry point is refused while the first reads a card`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The keys are held per environment, but the paypoint's held payment and its session repair are
            // not, so the gate is the entry point alone.
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(script(), readGate = { held.await() }).also { it.coordinator.initialize() }
            val first = chargeParkedInTheRead(fixture, held)

            val refusal = refusedAtOnce(runnerOver(fixture, PayabliEnvironment.PRODUCTION))

            assertEquals(PayabliErrorType.TERMINAL_NOT_READY, refusal.type)
            assertEquals("the refused charge sent an opening", 1, fixture.requestsTo(INITIATE).size)
            held.complete(Unit)
            completing("the first charge") { first.await() }.getOrThrow()
        }

    @Test
    fun `a refused retry with no key held is unknown for the running sale and not charged for neither`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(script(), readGate = { held.await() }).also { it.coordinator.initialize() }
            val first = chargeParkedInTheRead(fixture, held)
            fixture.keys.settle(ENTRY, PayabliEnvironment.SANDBOX, "$MINTED_KEY-${fixture.minted.get()}")

            val running = refusedAtOnce(runnerOver(fixture), PayabliAttempt.Retry)
            val neither = refusedAtOnce(runnerOver(fixture), PayabliAttempt.Retry, details("99.99"))

            assertEquals(PayabliErrorType.TERMINAL_NOT_READY, running.type)
            assertEquals("the running sale", TapToPayCapture.UNKNOWN, running.capture)
            assertEquals("neither sale", TapToPayCapture.NOT_CHARGED, neither.capture)
            assertEquals("a refused retry sent an opening", 1, fixture.requestsTo(INITIATE).size)
            held.complete(Unit)
            completing("the first charge") { first.await() }.getOrThrow()
        }

    @Test
    fun `a retry for the running sale is unknown once its key settles, though an older sale is still held`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(script(), readGate = { held.await() }).also { it.coordinator.initialize() }
            val older = holdUnknownSale(fixture, details(OLDER_SALE))
            val first = chargeParkedInTheRead(fixture, held)
            fixture.keys.settle(ENTRY, PayabliEnvironment.SANDBOX, "$MINTED_KEY-${fixture.minted.get()}")

            val refusal = refusedAtOnce(runnerOver(fixture), PayabliAttempt.Retry)

            assertEquals(TapToPayCapture.UNKNOWN, refusal.capture)
            assertTrue(
                "the older sale's key was dropped",
                fixture.keys.holdsRetryOf(ENTRY, PayabliEnvironment.SANDBOX, older),
            )
            held.complete(Unit)
            completing("the first charge") { first.await() }.getOrThrow()
        }

    @Test
    fun `a retry for an older held sale, refused while another sale runs, is unknown and keeps its key`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(script(), readGate = { held.await() }).also { it.coordinator.initialize() }
            val older = holdUnknownSale(fixture, details(OLDER_SALE))
            val first = chargeParkedInTheRead(fixture, held)

            val refusal = refusedAtOnce(runnerOver(fixture), PayabliAttempt.Retry, details(OLDER_SALE))

            assertEquals(TapToPayCapture.UNKNOWN, refusal.capture)
            assertEquals("a refused retry sent an opening", 1, fixture.requestsTo(INITIATE).size)
            held.complete(Unit)
            completing("the first charge") { first.await() }.getOrThrow()
            assertTrue(
                "the older sale's key was dropped",
                fixture.keys.holdsRetryOf(ENTRY, PayabliEnvironment.SANDBOX, older),
            )
        }

    /**
     * Holds a key for [payment] whose send ended unknown, as an earlier charge on [fixture]'s entry point leaves it.
     *
     * @param fixture the session whose key store holds the sale.
     * @param payment the sale the earlier charge described.
     * @param entry the entry point the earlier charge was made on.
     * @return the held sale, as a retry for it is compared.
     */
    private suspend fun holdUnknownSale(
        fixture: SessionFixture,
        payment: TapToPayPaymentDetails,
        entry: String = ENTRY,
    ): ChargeDescription {
        val sale = ChargeDescription.of(payment, TapToPayInvoiceData())
        val reserved = fixture.keys.reserve(entry, PayabliEnvironment.SANDBOX, PayabliAttempt.NewPayment, sale)
        fixture.keys.supersede(entry, PayabliEnvironment.SANDBOX, reserved.key)
        return sale
    }

    @Test
    fun `a retry refused for a running charge of a different sale is not charged`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(script(), readGate = { held.await() }).also { it.coordinator.initialize() }
            val first = chargeParkedInTheRead(fixture, held)

            val refusal = refusedAtOnce(runnerOver(fixture), PayabliAttempt.Retry, details("99.99"))

            assertEquals(TapToPayCapture.NOT_CHARGED, refusal.capture)
            assertEquals("the refused retry sent an opening", 1, fixture.requestsTo(INITIATE).size)
            held.complete(Unit)
            completing("the first charge") { first.await() }.getOrThrow()
        }

    @Test
    fun `a retry while its key is being sent is refused as open, then resends it once that charge ends unknown`() =
        runTest(timeout = TEST_TIMEOUT) {
            val sending = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture =
                SessionFixture(
                    script(updates = 2, opens = 2),
                    sendGate = { request ->
                        if (request.path == INITIATE && !release.isCompleted) {
                            sending.complete(Unit)
                            release.await()
                        }
                    },
                ).also { it.coordinator.initialize() }
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = "WAITING"))
            val first =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
                }
            assertTrue("the first charge is sending its opening", sending.isCompleted)

            val refusal = refusedAtOnce(runnerOver(fixture), PayabliAttempt.Retry)

            assertEquals(PayabliErrorType.PAYMENT_REFERENCE_OPEN, refusal.type)
            assertEquals("the sale being sent was reported as not charged", TapToPayCapture.UNKNOWN, refusal.capture)
            assertEquals("the refused retry sent an opening", 1, fixture.requestsTo(INITIATE).size)
            release.complete(Unit)
            completing("the first charge") { first.await() }
            runCatching {
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null, PayabliAttempt.Retry)
            }
            assertEquals("the retry did not resend the unknown charge's key", "$MINTED_KEY-1", fixture.keySent(1))
        }

    @Test
    fun `a retry while its key's charge waits for a card is refused as open and sends nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(script(), readGate = { held.await() }).also { it.coordinator.initialize() }
            val first = chargeParkedInTheRead(fixture, held)
            val reserved = fixture.minted.get()

            val refusal = refusedAtOnce(runnerOver(fixture), PayabliAttempt.Retry)

            assertEquals(PayabliErrorType.PAYMENT_REFERENCE_OPEN, refusal.type)
            assertEquals("the refused retry reserved a key", reserved, fixture.minted.get())
            assertEquals("the refused retry sent an opening", 1, fixture.requestsTo(INITIATE).size)
            held.complete(Unit)
            completing("the first charge") { first.await() }.getOrThrow()
        }

    @Test
    fun `a charge on another entry point is refused at once while one reads a card`() =
        runTest(timeout = TEST_TIMEOUT) {
            val other = readyFixture()
            val device = checkNotNull(other.enrollment.store.read(ENTRY))
            other.enrollment.store.write(AttestedDevice(ANOTHER_ENTRY, device.deviceId, device.keyId))
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(script(), readGate = { held.await() }).also { it.coordinator.initialize() }
            val first = chargeParkedInTheRead(fixture, held)
            val reserved = other.minted.get()

            // Released however this ends, so a failure here does not leave the reader held for later tests.
            val refusal =
                try {
                    refusedAtOnce(runnerOver(other, entry = ANOTHER_ENTRY))
                } finally {
                    held.complete(Unit)
                }

            assertEquals(PayabliErrorType.TERMINAL_NOT_READY, refusal.type)
            assertEquals(TapToPayCapture.NOT_CHARGED, refusal.capture)
            assertEquals("the refused charge reserved a key", reserved, other.minted.get())
            assertTrue("the refused charge sent an opening", other.requestsTo(INITIATE).isEmpty())
            completing("the first charge") { first.await() }.getOrThrow()
        }

    @Test
    fun `a retry refused on another entry point is unknown when that entry point holds its sale`() =
        runTest(timeout = TEST_TIMEOUT) {
            val other = readyFixture()
            val device = checkNotNull(other.enrollment.store.read(ENTRY))
            other.enrollment.store.write(AttestedDevice(ANOTHER_ENTRY, device.deviceId, device.keyId))
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(script(), readGate = { held.await() }).also { it.coordinator.initialize() }
            val older = holdUnknownSale(other, details(OLDER_SALE), entry = ANOTHER_ENTRY)
            val first = chargeParkedInTheRead(fixture, held)

            val refusal =
                try {
                    refusedAtOnce(runnerOver(other, entry = ANOTHER_ENTRY), PayabliAttempt.Retry, details(OLDER_SALE))
                } finally {
                    held.complete(Unit)
                }

            assertEquals(PayabliErrorType.TERMINAL_NOT_READY, refusal.type)
            assertEquals(TapToPayCapture.UNKNOWN, refusal.capture)
            completing("the first charge") { first.await() }.getOrThrow()
            assertTrue(
                "the held sale's key was dropped",
                other.keys.holdsRetryOf(ANOTHER_ENTRY, PayabliEnvironment.SANDBOX, older),
            )
        }

    @Test
    fun `a charge withdrawn between its claim and its lock lets the close behind it go and frees its entry point`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A close on another paypoint of the same stripe holds the lock, so this charge is claimed but not locked.
            val stripe = { entry: String -> (entry.hashCode().toLong() and 0x7fffffffL).toInt() % 16 }
            assertEquals("the entry points share a lock stripe", stripe(ENTRY), stripe(STRIPE_SHARING_ENTRY))
            val other = readyFixture()
            val device = checkNotNull(other.enrollment.store.read(ENTRY))
            other.enrollment.store.write(AttestedDevice(STRIPE_SHARING_ENTRY, device.deviceId, device.keyId))
            var closeFails = true
            val closing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture =
                SessionFixture(
                    scriptWithCloseControl(opens = 1, closes = 4) { closeFails },
                    sendGate = { request ->
                        if (request.path == UPDATE && !closeFails && !release.isCompleted) {
                            closing.complete(Unit)
                            release.await()
                        }
                    },
                ).also { it.coordinator.initialize() }
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            closeFails = false
            val holding =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    runCatching { runnerOver(fixture).closeCaptured(TRANS_ID) }
                }
            val terminal = runnerOver(other, entry = STRIPE_SHARING_ENTRY)

            // Released however this ends, so a failure here does not leave the region held for later tests.
            val close =
                try {
                    assertTrue("the close is being sent", closing.isCompleted)
                    val waiting =
                        async(UnconfinedTestDispatcher(testScheduler)) {
                            terminal.charge(details(), PAYER, TapToPayInvoiceData(), null)
                        }
                    assertFalse("the charge took the lock", waiting.isCompleted)
                    val close =
                        async(UnconfinedTestDispatcher(testScheduler)) {
                            runCatching { runnerOver(other, entry = STRIPE_SHARING_ENTRY).closeCaptured(TRANS_ID) }
                        }
                    waiting.cancel()
                    waiting.join()
                    close
                } finally {
                    release.complete(Unit)
                }
            completing("the close holding the lock") { holding.await() }.getOrThrow()

            assertTrue("the close still waits for the withdrawn charge", close.isCompleted)
            val receipt = terminal.charge(details(), PAYER, TapToPayInvoiceData(), null)
            assertEquals("the entry point was still held", TRANS_ID, receipt.paymentTransId)
        }

    @Test
    fun `an entry point takes a charge again however the last one ended`() =
        runTest(timeout = TEST_TIMEOUT) {
            suspend fun takesACharge(after: String) {
                val receipt =
                    runCatching { runnerOver(readyFixture()).charge(details(), PAYER, TapToPayInvoiceData(), null) }
                assertEquals(
                    "the entry point was still held after $after",
                    TRANS_ID,
                    receipt.getOrNull()?.paymentTransId,
                )
            }

            runnerOver(readyFixture()).charge(details(), PAYER, TapToPayInvoiceData(), null)
            takesACharge("an approval")

            val answered =
                listOf(
                    "a decline" to CardReadOutcome.DECLINED,
                    "an unknown outcome" to CardReadOutcome.INDETERMINATE,
                )
            for ((ending, outcome) in answered) {
                val fixture = readyFixture()
                fixture.reader.answerReadWith(cardRead(outcome = outcome, providerState = "WAITING"))
                assertTrue(
                    runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }.isFailure,
                )
                takesACharge(ending)
            }

            val radioOff =
                runCatching {
                    runnerOver(
                        readyFixture(),
                        contactlessEnabled = { false },
                    ).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }
            assertEquals(PayabliErrorType.CONTACTLESS_DISABLED, (radioOff.exceptionOrNull() as TapToPayException).type)
            takesACharge("a refusal before anything was sent")

            val parked =
                SessionFixture(
                    script(),
                    readGate = { awaitCancellation() },
                ).also { it.coordinator.initialize() }
            val withdrawn =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    runnerOver(parked).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }
            assertEquals("the charge is waiting for a card", waiting, parked.state)
            withdrawn.cancel()
            withdrawn.join()
            takesACharge("a withdrawal during the read")

            runCatching { runnerOver(readyFixture()).closeCaptured("12-not-held") }
            takesACharge("a close of a payment not held")
        }

    @Test
    fun `a charge arriving while a captured close is sent is refused at once, and the close still lands`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Three answers for the charge's close that gives up, one for the recovery, one for the next charge.
            var closeFails = true
            val closing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture =
                SessionFixture(
                    scriptWithCloseControl(opens = 2, closes = 5) { closeFails },
                    sendGate = { request ->
                        if (request.path == UPDATE && !closeFails && !release.isCompleted) {
                            closing.complete(Unit)
                            release.await()
                        }
                    },
                ).also { it.coordinator.initialize() }
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            closeFails = false
            val close =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    runCatching { runnerOver(fixture).closeCaptured(TRANS_ID) }
                }
            assertTrue("the close is being sent", closing.isCompleted)
            val reserved = fixture.minted.get()

            // Released however this ends, so a failure here does not leave the region held for later tests.
            try {
                for (attempt in listOf(PayabliAttempt.NewPayment, PayabliAttempt.Retry)) {
                    val refusal = refusedAtOnce(runnerOver(fixture), attempt)
                    assertEquals("$attempt", PayabliErrorType.TERMINAL_NOT_READY, refusal.type)
                    // The retry describes the sale whose close gave up, so its money may have moved.
                    val capture =
                        if (attempt == PayabliAttempt.Retry) TapToPayCapture.UNKNOWN else TapToPayCapture.NOT_CHARGED
                    assertEquals("$attempt", capture, refusal.capture)
                }
            } finally {
                release.complete(Unit)
            }
            assertEquals("a refused charge reserved a key", reserved, fixture.minted.get())
            assertEquals("a refused charge sent an opening", 1, fixture.requestsTo(INITIATE).size)
            completing("the close") { close.await() }.getOrThrow()
            val next = runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
            assertEquals("the entry point was still held after the close", TRANS_ID, next.paymentTransId)
        }

    @Test
    fun `a retry refused while only a close holds the entry point is unknown until the close settles its key`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Three answers for the charge's close that gives up, one for the close that lands.
            var closeFails = true
            val closing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture =
                SessionFixture(
                    scriptWithCloseControl(opens = 1, closes = 4) { closeFails },
                    sendGate = { request ->
                        if (request.path == UPDATE && !closeFails && !release.isCompleted) {
                            closing.complete(Unit)
                            release.await()
                        }
                    },
                ).also { it.coordinator.initialize() }
            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
            closeFails = false
            val close =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    runCatching { runnerOver(fixture).closeCaptured(TRANS_ID) }
                }
            // A second close of the same payment, counted on the entry point and queued behind the first.
            val again =
                async(StandardTestDispatcher(testScheduler)) {
                    runCatching { runnerOver(fixture).closeCaptured(TRANS_ID) }
                }
            testScheduler.runCurrent()
            assertTrue("the close is being sent", closing.isCompleted)

            val whileHeld =
                try {
                    refusedAtOnce(runnerOver(fixture), PayabliAttempt.Retry)
                } finally {
                    release.complete(Unit)
                }
            assertEquals(PayabliErrorType.TERMINAL_NOT_READY, whileHeld.type)
            assertEquals("the close's key is still held", TapToPayCapture.UNKNOWN, whileHeld.capture)

            assertTrue("the first close landed", close.isCompleted)
            assertFalse("the second close released the entry point", again.isCompleted)
            val afterSettle = refusedAtOnce(runnerOver(fixture), PayabliAttempt.Retry)
            assertEquals(PayabliErrorType.TERMINAL_NOT_READY, afterSettle.type)
            assertEquals("the close settled its key", TapToPayCapture.NOT_CHARGED, afterSettle.capture)

            close.await().getOrThrow()
            testScheduler.runCurrent()
            assertTrue("the second close never ended", again.isCompleted)
            assertEquals("a refused retry sent an opening", 1, fixture.requestsTo(INITIATE).size)
        }

    @Test
    fun `a retry whose key a running charge is sending is refused as open while a close waits behind it`() =
        runTest(timeout = TEST_TIMEOUT) {
            val sending = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture =
                SessionFixture(
                    script(),
                    sendGate = { request ->
                        if (request.path == INITIATE && !release.isCompleted) {
                            sending.complete(Unit)
                            release.await()
                        }
                    },
                ).also { it.coordinator.initialize() }
            val first =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
                }
            assertTrue("the first charge is sending its opening", sending.isCompleted)
            val close =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    runCatching { runnerOver(fixture).closeCaptured(TRANS_ID) }
                }
            assertFalse("the close did not wait for the charge", close.isCompleted)
            val reserved = fixture.minted.get()

            // Released however this ends, so a failure here does not leave the region held for later tests.
            val refusal =
                try {
                    refusedAtOnce(runnerOver(fixture), PayabliAttempt.Retry)
                } finally {
                    release.complete(Unit)
                }

            assertEquals(PayabliErrorType.PAYMENT_REFERENCE_OPEN, refusal.type)
            assertEquals("the refused retry reserved a key", reserved, fixture.minted.get())
            assertEquals("the refused retry sent an opening", 1, fixture.requestsTo(INITIATE).size)
            completing("the first charge") { first.await() }.getOrThrow()
            completing("the close") { close.await() }
        }

    @Test
    fun `a close arriving during a charge waits for it, then sends`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The charge's own close gives up after three answers, which leaves its payment held for the close
            // that waited.
            var closes = 0
            val held = CompletableDeferred<Unit>()
            val fixture =
                SessionFixture(
                    scriptWithCloseControl(closes = 4) { ++closes <= 3 },
                    readGate = { held.await() },
                ).also { it.coordinator.initialize() }
            val charge = chargeParkedInTheRead(fixture, held)

            val close =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    runCatching { runnerOver(fixture).closeCaptured(TRANS_ID) }
                }
            assertFalse("the close did not wait for the charge", close.isCompleted)
            held.complete(Unit)

            // Awaited without a completion deadline: the failed close's retries back off past one.
            val unclosed = charge.await().exceptionOrNull() as TapToPayException
            assertEquals(PayabliErrorType.PAYMENT_NOT_CLOSED, unclosed.type)
            completing("the close") { close.await() }.getOrThrow()
            assertEquals("the waiting close was not sent", 4, fixture.requestsTo(UPDATE).size)
        }

    @Test
    fun `a retry refused while a close outlasts the charge it queued behind reads the held keys alone`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture = SessionFixture(script(), readGate = { held.await() }).also { it.coordinator.initialize() }
            val charge = chargeParkedInTheRead(fixture, held)
            // Runs only when the scheduler is advanced, so it still holds the entry point after the charge leaves.
            val close =
                async(StandardTestDispatcher(testScheduler)) {
                    runCatching { runnerOver(fixture).closeCaptured(TRANS_ID) }
                }
            testScheduler.runCurrent()

            held.complete(Unit)
            assertTrue("the charge did not end", charge.isCompleted)
            charge.await().getOrThrow()
            assertFalse("the close released the entry point", close.isCompleted)

            val refusal = refusedAtOnce(runnerOver(fixture), PayabliAttempt.Retry)
            assertEquals(PayabliErrorType.TERMINAL_NOT_READY, refusal.type)
            assertEquals("the finished charge's sale was still counted", TapToPayCapture.NOT_CHARGED, refusal.capture)
            testScheduler.runCurrent()
            assertTrue("the close never ended", close.isCompleted)
        }

    @Test
    fun `a close withdrawn while it waits for a charge leaves the entry point free`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture =
                SessionFixture(script(updates = 2, opens = 2), readGate = { held.await() })
                    .also { it.coordinator.initialize() }
            val charge = chargeParkedInTheRead(fixture, held)
            val close =
                launch(UnconfinedTestDispatcher(testScheduler)) { runnerOver(fixture).closeCaptured(TRANS_ID) }

            close.cancel()
            close.join()
            held.complete(Unit)
            completing("the charge") { charge.await() }.getOrThrow()

            val next = runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
            assertEquals("the withdrawn close still held the entry point", TRANS_ID, next.paymentTransId)
        }

    @Test
    fun `a payment held by one terminal is the same payment another terminal holds`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Held per instance, the second terminal's payment hides the first's and both believe they hold
            // one. Whichever settles the attempt first leaves the other to mint a fresh key and charge again.
            var closeFails = true
            val fixture =
                SessionFixture(scriptWithCloseControl(opens = 2, closes = 4) { closeFails })
                    .also { it.coordinator.initialize() }
            val first = runnerOver(fixture)
            val second = runnerOver(fixture)

            runCatching { first.charge(details(), PAYER, TapToPayInvoiceData(), null) }
            closeFails = false
            second.charge(details(), PAYER, TapToPayInvoiceData(), null)

            // The second terminal opened a payment, which drops what any terminal was holding for this
            // paypoint, so the first has nothing left to close.
            val refusal = runCatching { first.closeCaptured(TRANS_ID) }.exceptionOrNull()
            assertTrue(refusal.toString(), refusal is TapToPayException)
            assertEquals(TapToPayCapture.UNKNOWN, (refusal as TapToPayException).capture)
        }

    @Test
    fun `a payment that closed is no longer held`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Nothing is kept once the close lands, so the processor metadata the recovery held is
            // released with it.
            val fixture = readyFixture()
            val runner = runnerOver(fixture)
            val receipt = runner.charge(details(), PAYER, TapToPayInvoiceData(), null)

            val refusal = runCatching { runner.closeCaptured(receipt.paymentTransId) }.exceptionOrNull()

            assertTrue(refusal.toString(), refusal is TapToPayException)
            assertEquals(TapToPayCapture.UNKNOWN, (refusal as TapToPayException).capture)
        }

    @Test
    fun `a payment held for one environment is not offered to a terminal on another`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A session that needs reinitializing admits any configuration next, so one process can hold a
            // payment opened against sandbox and then build a terminal for the same entry point against
            // production. Keyed on the entry point alone, that terminal closes a payment the production
            // service never opened.
            var closeFails = true
            val fixture =
                SessionFixture(scriptWithCloseControl(opens = 1, closes = 3) { closeFails })
                    .also { it.coordinator.initialize() }
            runCatching {
                runnerOver(fixture, PayabliEnvironment.SANDBOX)
                    .charge(details(), PAYER, TapToPayInvoiceData(), null)
            }

            closeFails = false
            val elsewhere = runnerOver(fixture, PayabliEnvironment.PRODUCTION)
            val refusal = runCatching { elsewhere.closeCaptured(TRANS_ID) }.exceptionOrNull()

            assertTrue(refusal.toString(), refusal is TapToPayException)
            assertEquals(TapToPayCapture.UNKNOWN, (refusal as TapToPayException).capture)
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
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }

            closeFails = false
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))
            runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }

            val refusal = runCatching { runner.closeCaptured(TRANS_ID) }.exceptionOrNull()
            assertTrue(refusal.toString(), refusal is TapToPayException)
            assertEquals(TapToPayCapture.UNKNOWN, (refusal as TapToPayException).capture)
        }

    @Test
    fun `a refused card whose close failed reports the close failure beside the refusal`() =
        runTest(timeout = TEST_TIMEOUT) {
            recording { recorded ->
                val fixture =
                    SessionFixture(scriptWithCloseControl(closes = 3) { true })
                        .also { it.coordinator.initialize() }
                fixture.reader.answerReadWith(
                    cardRead(outcome = CardReadOutcome.DECLINED, providerState = "DECLINED"),
                )

                runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

                assertEquals(
                    listOf(TelemetryProperties.Origin.CHARGE),
                    originsOf(recorded, TelemetryEvents.TTP_CLOSE_STARTED),
                )
                assertEquals(
                    listOf(TelemetryProperties.Origin.CHARGE),
                    originsOf(recorded, TelemetryEvents.TTP_CLOSE_FAILED),
                )
                val charge = recorded.single { it.first == TelemetryEvents.TTP_CHARGE_FAILED }.second
                assertEquals(TelemetryProperties.Outcome.DECLINED, charge[TelemetryProperty.OUTCOME.key])
            }
        }

    @Test
    fun `a charge whose close landed reports the close under the charge's origin`() =
        runTest(timeout = TEST_TIMEOUT) {
            recording { recorded ->
                val fixture = readyFixture()

                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)

                assertEquals(
                    listOf(TelemetryProperties.Origin.CHARGE),
                    originsOf(recorded, TelemetryEvents.TTP_CLOSE_SUCCEEDED),
                )
            }
        }

    @Test
    fun `a tap that failed reports the close it sent on the way out`() =
        runTest(timeout = TEST_TIMEOUT) {
            recording { recorded ->
                val fixture = readyFixture()
                fixture.reader.failNextRead(CardReaderException.ReadFailed(null))

                runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

                assertEquals(
                    listOf(TelemetryProperties.Origin.CHARGE),
                    originsOf(recorded, TelemetryEvents.TTP_CLOSE_SUCCEEDED),
                )
            }
        }

    @Test
    fun `a recorder error after a landed close is not reported as a failed close`() =
        runTest(timeout = TEST_TIMEOUT) {
            val recorded = mutableListOf<String>()
            TelemetryRecorders.install { event, _ ->
                if (event == TelemetryEvents.TTP_CLOSE_SUCCEEDED) throw OutOfMemoryError("the heap is gone")
                recorded += event
            }
            try {
                val fixture = readyFixture()
                fixture.reader.failNextRead(CardReaderException.ReadFailed(null))

                val failure =
                    runCatching {
                        runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                    }.exceptionOrNull()

                assertTrue(failure.toString(), failure is OutOfMemoryError)
                assertFalse(
                    "a landed close was reported as failed: $recorded",
                    TelemetryEvents.TTP_CLOSE_FAILED in recorded,
                )
            } finally {
                TelemetryRecorders.clear()
            }
        }

    @Test
    fun `a host retrying a close reports it as a retry`() =
        runTest(timeout = TEST_TIMEOUT) {
            recording { recorded ->
                var closeFails = true
                val fixture =
                    SessionFixture(scriptWithCloseControl(closes = 4) { closeFails })
                        .also { it.coordinator.initialize() }
                val runner = runnerOver(fixture)
                runCatching { runner.charge(details(), PAYER, TapToPayInvoiceData(), null) }

                closeFails = false
                runner.closeCaptured(TRANS_ID)

                assertEquals(
                    listOf(TelemetryProperties.Origin.CHARGE, TelemetryProperties.Origin.RETRY),
                    originsOf(recorded, TelemetryEvents.TTP_CLOSE_STARTED),
                )
                assertEquals(
                    listOf(TelemetryProperties.Origin.RETRY),
                    originsOf(recorded, TelemetryEvents.TTP_CLOSE_SUCCEEDED),
                )
            }
        }

    /** Every state [fixture] publishes from here on. Unconfined, so a state that is replaced is still seen. */
    private fun TestScope.statesOf(fixture: SessionFixture): List<TapToPaySessionState> {
        val seen = mutableListOf<TapToPaySessionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.manager.state.collect { seen += it } }
        return seen
    }

    private val opening = TapToPaySessionState.Charging(TapToPayChargeActivity.OPENING)
    private val waiting = TapToPaySessionState.Charging(TapToPayChargeActivity.WAITING_FOR_CARD)
    private val closing = TapToPaySessionState.Charging(TapToPayChargeActivity.CLOSING)

    @Test
    fun `a charge reports opening, waiting for a card and closing, then ready`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()
            val seen = statesOf(fixture)

            runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)

            assertEquals(
                listOf(TapToPaySessionState.Ready, opening, waiting, closing, TapToPaySessionState.Ready),
                seen,
            )
        }

    @Test
    fun `a refused card ends the charge on ready`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.DECLINED, providerState = "DECLINED"))
            val seen = statesOf(fixture)

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            assertEquals(
                listOf(TapToPaySessionState.Ready, opening, waiting, closing, TapToPaySessionState.Ready),
                seen,
            )
        }

    @Test
    fun `an outcome that is never definite ends the charge on ready`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()
            fixture.reader.answerReadWith(cardRead(outcome = CardReadOutcome.INDETERMINATE, providerState = null))
            val seen = statesOf(fixture)

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            assertEquals(
                listOf(TapToPaySessionState.Ready, opening, waiting, closing, TapToPaySessionState.Ready),
                seen,
            )
        }

    @Test
    fun `a close that failed ends the charge on ready`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    scriptWithCloseControl(closes = 3) { true },
                ).also { it.coordinator.initialize() }
            val seen = statesOf(fixture)

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            assertEquals(
                listOf(TapToPaySessionState.Ready, opening, waiting, closing, TapToPaySessionState.Ready),
                seen,
            )
        }

    @Test
    fun `an opening the service refused ends the charge on ready without asking for a card`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture =
                SessionFixture(
                    RouteScript(
                        RouteScript.CHALLENGE to listOf(challengeBody()),
                        RouteScript.REGISTER to listOf(registerBody(status = "active")),
                        RouteScript.ATTEST to listOf(attestBody()),
                        RouteScript.CONFIG to listOf(configBody()),
                        INITIATE to listOf(rejected()),
                    ),
                ).also { it.coordinator.initialize() }
            val seen = statesOf(fixture)

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            assertEquals(listOf(TapToPaySessionState.Ready, opening, TapToPaySessionState.Ready), seen)
        }

    @Test
    fun `a tap that failed closes and ends the charge on ready`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()
            fixture.reader.failNextRead(CardReaderException.ReadFailed(null))
            val seen = statesOf(fixture)

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            assertEquals(
                listOf(TapToPaySessionState.Ready, opening, waiting, closing, TapToPaySessionState.Ready),
                seen,
            )
        }

    @Test
    fun `a withdrawn charge ends on ready`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()
            fixture.reader.failNextRead(CancellationException("the host withdrew"))
            val seen = statesOf(fixture)

            val outcome = runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            assertTrue(outcome.exceptionOrNull() is CancellationException)
            assertEquals(
                listOf(TapToPaySessionState.Ready, opening, waiting, closing, TapToPaySessionState.Ready),
                seen,
            )
        }

    @Test
    fun `a dead reader session closes, then ends the charge expired, not ready`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()
            fixture.reader.failNextRead(CardReaderException.SessionUnusable(null))
            val seen = statesOf(fixture)

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            assertEquals(
                listOf(TapToPaySessionState.Ready, opening, waiting, closing, TapToPaySessionState.SessionExpired),
                seen,
            )
        }

    @Test
    fun `a denial during the tap closes, then ends the charge expired`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()
            fixture.reader.failNextRead(CardReaderException.DeviceDenied(null))
            val seen = statesOf(fixture)

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            assertEquals(
                listOf(TapToPaySessionState.Ready, opening, waiting, closing, TapToPaySessionState.SessionExpired),
                seen,
            )
        }

    @Test
    fun `a lost device record ends the charge failed, needing the device set up again`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()
            fixture.enrollment.store.clear(ENTRY)
            val seen = statesOf(fixture)

            runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }

            assertEquals(
                listOf(
                    TapToPaySessionState.Ready,
                    opening,
                    TapToPaySessionState.Failed(TapToPayFailureReason.DEVICE_SETUP_REQUIRED),
                ),
                seen,
            )
        }

    @Test
    fun `a charge is not ready while it waits for a card`() =
        runTest(timeout = TEST_TIMEOUT) {
            var during: Pair<TapToPaySessionState, Boolean>? = null
            lateinit var fixture: SessionFixture
            fixture =
                SessionFixture(script(), readGate = { during = fixture.state to fixture.manager.isReady.value })
                    .also { it.coordinator.initialize() }

            runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)

            assertEquals(waiting to false, during)
            assertTrue(fixture.manager.isReady.value)
        }

    @Test
    fun `a session started over during a read keeps its new state, and the charge keeps its outcome`() =
        runTest(timeout = TEST_TIMEOUT) {
            // The manager is moved directly: a setup call made during the read waits for the charge, so this
            // holds the exit's own rule, that it writes ready only over a charging state.
            lateinit var fixture: SessionFixture
            fixture =
                SessionFixture(script(), readGate = { fixture.manager.reset() }).also { it.coordinator.initialize() }

            val receipt = runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)

            assertEquals(TRANS_ID, receipt.paymentTransId)
            assertEquals(TapToPaySessionState.Idle, fixture.state)
        }

    /** Enough device answers for [builds] session builds, a payment, and its close. */
    private fun scriptForBuilds(builds: Int) =
        RouteScript(
            RouteScript.CHALLENGE to List(builds) { challengeBody() },
            RouteScript.REGISTER to List(builds) { registerBody(status = "active") },
            RouteScript.ATTEST to List(builds) { attestBody() },
            RouteScript.CONFIG to List(builds) { configBody() },
            INITIATE to listOf(approved("""{"paymentTransId":"$TRANS_ID"}""")),
            UPDATE to listOf("{}"),
        )

    /**
     * Starts a charge and returns it parked in the read, where it stays until [held] completes.
     *
     * @param fixture the ready session the charge runs over.
     * @param held completed by the test to let the read answer.
     * @param terminal the terminal the charge is made on.
     * @return the running charge, holding its outcome rather than failing the test's scope with it.
     */
    private fun TestScope.chargeParkedInTheRead(
        fixture: SessionFixture,
        held: CompletableDeferred<Unit>,
        terminal: TapToPayChargeRunner = runnerOver(fixture),
    ) = async(UnconfinedTestDispatcher(testScheduler)) {
        runCatching { terminal.charge(details(), PAYER, TapToPayInvoiceData(), null) }
    }.also {
        assertEquals("the charge is waiting for a card", waiting, fixture.state)
        assertFalse(held.isCompleted)
    }

    @Test
    fun `an initialize made during a read sends nothing until the charge ends`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture =
                SessionFixture(scriptForBuilds(2), readGate = { held.await() }).also { it.coordinator.initialize() }
            val charge = chargeParkedInTheRead(fixture, held)
            val sentDuringTheRead = fixture.routes.toList()

            val setup = launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.initialize() }

            assertTrue("the initialize is waiting for the charge", setup.isActive)
            assertEquals("the initialize sent nothing during the read", sentDuringTheRead, fixture.routes)
            assertEquals(waiting, fixture.state)
            assertFalse(fixture.manager.isReady.value)

            held.complete(Unit)
            val receipt = completing("the charge") { charge.await() }.getOrThrow()
            completing("the initialize") { setup.join() }

            assertEquals(TRANS_ID, receipt.paymentTransId)
            val afterTheClose = fixture.routes.drop(fixture.routes.lastIndexOf(UPDATE) + 1)
            assertEquals("the initialize ran once the charge ended", RouteScript.CONFIG, afterTheClose.lastOrNull())
            assertEquals(TapToPaySessionState.Ready, fixture.state)
        }

    @Test
    fun `an activation made during a read waits for the charge to end`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture =
                SessionFixture(scriptForBuilds(1), readGate = { held.await() }).also { it.coordinator.initialize() }
            val charge = chargeParkedInTheRead(fixture, held)

            val activation =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    runCatching { fixture.coordinator.activateDevice("123456") }.exceptionOrNull()
                }

            assertTrue("the activation is waiting for the charge", activation.isActive)

            held.complete(Unit)
            completing("the charge") { charge.await() }.getOrThrow()
            val refused = completing("the activation") { activation.await() }

            // Read at its turn, after the charge, so it is refused from ready rather than from charging.
            assertTrue(refused.toString(), refused is DeviceActivationException.DeviceNotPending)
            assertFalse("an activation was sent", RouteScript.ACTIVATE in fixture.routes)
            assertEquals(TapToPaySessionState.Ready, fixture.state)
        }

    @Test
    fun `a repair made during a read waits for the charge to end`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture =
                SessionFixture(scriptForBuilds(1), readGate = { held.await() }).also { it.coordinator.initialize() }
            val charge = chargeParkedInTheRead(fixture, held)

            val repair =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    runCatching { fixture.coordinator.reinitializeIfNeeded() }
                }

            assertTrue("the repair is waiting for the charge", repair.isActive)

            held.complete(Unit)
            completing("the charge") { charge.await() }.getOrThrow()

            // At its turn the session is ready, which is the one state a repair leaves alone.
            completing("the repair") { repair.await() }.getOrThrow()
            assertEquals(TapToPaySessionState.Ready, fixture.state)
            assertEquals(1, fixture.reader.prepareCount)
        }

    @Test
    fun `a dead reader during a charge expires that session, and an initialize queued behind it rebuilds a live one`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val fixture =
                SessionFixture(scriptForBuilds(2), readGate = {
                    held.await()
                    throw CardReaderException.SessionUnusable(null)
                }).also { it.coordinator.initialize() }
            val seen = statesOf(fixture)
            val charge = chargeParkedInTheRead(fixture, held)
            val setup = launch(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.initialize() }

            held.complete(Unit)
            val failed = completing("the charge") { charge.await() }.exceptionOrNull()
            completing("the initialize") { setup.join() }

            assertTrue(failed.toString(), failed is TapToPayException)
            val expired = seen.indexOf(TapToPaySessionState.SessionExpired)
            assertEquals(
                "the dead reader expired the charge's own session",
                listOf(waiting, closing, TapToPaySessionState.SessionExpired),
                seen.subList(expired - 2, expired + 1),
            )
            assertTrue("the rebuild ran after the expiry", TapToPaySessionState.AttestingDevice in seen.drop(expired))
            assertEquals(TapToPaySessionState.Ready, fixture.state)
        }

    @Test
    fun `a charge withdrawn during its repair leaves the session for the next setup`() =
        runTest(timeout = TEST_TIMEOUT) {
            var armed = false
            val held = CompletableDeferred<Unit>()
            val fixture =
                SessionFixture(scriptForBuilds(3), readerGate = { if (armed) held.await() })
                    .also { it.coordinator.initialize() }
            fixture.manager.invalidate()
            armed = true

            val charge =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)
                }
            assertEquals("the repair is bringing the reader up", TapToPaySessionState.InitializingReader, fixture.state)

            charge.cancel()
            completing("the withdrawn charge") { charge.join() }
            armed = false

            assertEquals(TapToPaySessionState.Idle, fixture.state)
            completing("the initialize after it") { fixture.coordinator.initialize() }
            assertEquals(TapToPaySessionState.Ready, fixture.state)
        }

    @Test
    fun `a second terminal's initialize waits for the first terminal's charge, which keeps its session`() =
        runTest(timeout = TEST_TIMEOUT) {
            val held = CompletableDeferred<Unit>()
            val first =
                SessionFixture(scriptForBuilds(1), readGate = { held.await() }).also { it.coordinator.initialize() }
            val second = SessionFixture(scriptForBuilds(1))
            val charge = chargeParkedInTheRead(first, held)

            val setup = launch(UnconfinedTestDispatcher(testScheduler)) { second.coordinator.initialize() }

            assertTrue("the second terminal is waiting for the charge", setup.isActive)
            assertEquals("the second terminal sent nothing during the read", emptyList<String>(), second.routes)
            assertEquals(0, second.reader.configureCount)

            held.complete(Unit)
            val receipt = completing("the charge") { charge.await() }.getOrThrow()
            completing("the second initialize") { setup.join() }

            assertEquals(TRANS_ID, receipt.paymentTransId)
            assertEquals("the charge's own session was not expired", TapToPaySessionState.Ready, first.state)
            assertEquals(TapToPaySessionState.Ready, second.state)
        }

    @Test
    fun `a terminal whose session was replaced refuses a charge, opening nothing and leaving the reader alone`() =
        runTest(timeout = TEST_TIMEOUT) {
            var live = true
            val fixture = SessionFixture(script(), isLive = { live }).also { it.coordinator.initialize() }
            live = false

            val failure =
                runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
                    .exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertEquals(PayabliErrorType.SESSION_NOT_INITIALIZED, (failure as TapToPayException).type)
            assertEquals("the session this was created with was replaced or ended; create a new one", failure.reason)
            assertFalse("a payment was opened", INITIATE in fixture.routes)
            assertNull("the reader was asked for a card", fixture.reader.lastReadRequest)
            assertEquals(1, fixture.reader.prepareCount)
            assertEquals(TapToPaySessionState.Failed(TapToPayFailureReason.SESSION_ENDED), fixture.state)
        }

    @Test
    fun `a session replaced while its payment opens reads no card, and the close it attempts is refused unsent`() =
        runTest(timeout = TEST_TIMEOUT) {
            var live = true
            var closesAttempted = 0
            val fixture =
                SessionFixture(
                    script(),
                    sendGate = { request -> if (request.path == INITIATE) live = false },
                    isLive = { live },
                    // A finished session's transport refuses every request before sending it.
                    unsent = { request ->
                        if (request.path == UPDATE && !live) {
                            closesAttempted++
                            PayabliGenericException(PayabliErrorType.SESSION_NOT_INITIALIZED, "the session finished")
                        } else {
                            null
                        }
                    },
                ).also { it.coordinator.initialize() }
            val seen = statesOf(fixture)

            val failure =
                runCatching { runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null) }
                    .exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertEquals(PayabliErrorType.SESSION_NOT_INITIALIZED, (failure as TapToPayException).type)
            assertEquals(TRANS_ID, failure.paymentTransId)
            assertEquals(
                listOf(
                    TapToPaySessionState.Ready,
                    opening,
                    closing,
                    TapToPaySessionState.Failed(TapToPayFailureReason.SESSION_ENDED),
                ),
                seen,
            )
            assertEquals(TapToPayCapture.NOT_CHARGED, failure.capture)
            assertNull("the reader was asked for a card", fixture.reader.lastReadRequest)
            assertEquals("the close was not attempted exactly once", 1, closesAttempted)
            assertFalse("the replaced session sent a close", UPDATE in fixture.routes)
            assertEquals(TapToPaySessionState.Failed(TapToPayFailureReason.SESSION_ENDED), fixture.state)
        }

    @Test
    fun `a session still live once its payment opens reads the card and closes it`() =
        runTest(timeout = TEST_TIMEOUT) {
            var opened = false
            val fixture =
                SessionFixture(script(), sendGate = { request -> if (request.path == INITIATE) opened = true })
                    .also { it.coordinator.initialize() }

            val receipt = runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)

            assertTrue("the payment was never opened", opened)
            assertEquals(TRANS_ID, receipt.paymentTransId)
            assertEquals(TRANS_ID, fixture.reader.lastReadRequest?.merchantTransactionId)
            assertTrue("the payment was not closed", UPDATE in fixture.routes)
            assertEquals(TapToPaySessionState.Ready, fixture.state)
        }

    @Test
    fun `a read under way when its session is replaced finishes, stays held, and a new terminal closes it`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A session is replaced only once it has finished, and a finished session's transport refuses every
            // request before sending it, so the close this charge attempts never leaves the device.
            var live = true
            val old =
                SessionFixture(script(updates = 0), readGate = { live = false }, isLive = { live })
                    .also { it.coordinator.initialize() }
            val oldRunner =
                runnerGatedOnClose(old) {
                    if (!live) throw PayabliGenericException(PayabliErrorType.TOKEN_EXPIRED, "the session finished")
                }

            val failure =
                runCatching { oldRunner.charge(details(), PAYER, TapToPayInvoiceData(), null) }.exceptionOrNull()

            assertTrue(failure.toString(), failure is TapToPayException)
            assertEquals(PayabliErrorType.PAYMENT_NOT_CLOSED, (failure as TapToPayException).type)
            assertEquals("the read answered, so the card was charged", TapToPayCapture.CHARGED, failure.capture)
            assertEquals(TRANS_ID, failure.paymentTransId)
            assertFalse("the old session sent a close", UPDATE in old.routes)

            val current = SessionFixture(script())
            runnerOver(current).closeCaptured(TRANS_ID)

            assertEquals("the new terminal closed the held payment", listOf(UPDATE), current.routes)
        }

    @Test
    fun `a charge reports entering and leaving, and no move between its activities`() =
        runTest(timeout = TEST_TIMEOUT) {
            val fixture = readyFixture()
            recording { recorded ->
                runnerOver(fixture).charge(details(), PAYER, TapToPayInvoiceData(), null)

                val moves =
                    recorded
                        .filter { it.first == TelemetryEvents.TTP_SESSION_STATE_CHANGED }
                        .map { it.second[TelemetryProperty.FROM.key] to it.second[TelemetryProperty.TO.key] }
                assertEquals(listOf("ready" to "charging", "charging" to "ready"), moves)
            }
        }

    private inline fun <T> recording(block: (List<Pair<String, Map<String, String>>>) -> T): T {
        val recorded = mutableListOf<Pair<String, Map<String, String>>>()
        TelemetryRecorders.install { event, properties -> recorded += event to properties }
        try {
            return block(recorded)
        } finally {
            TelemetryRecorders.clear()
        }
    }

    private fun originsOf(
        recorded: List<Pair<String, Map<String, String>>>,
        event: String,
    ): List<String?> = recorded.filter { it.first == event }.map { it.second[TelemetryProperty.ORIGIN.key] }
}
