package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.model.PayabliAttempt
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliFieldError
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.model.PayabliValidationException
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.telemetry.SessionScopedRecorder
import com.payabli.sdk.core.telemetry.TelemetryDeviceContext
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorder
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.core.telemetry.TelemetrySessionContext
import com.payabli.sdk.payin.client.FakePayInTransport
import com.payabli.sdk.payin.client.MoneyInClient
import com.payabli.sdk.payin.client.TokenStorageClient
import com.payabli.sdk.payin.client.testDetails
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInPaymentDetails
import com.payabli.sdk.payin.model.PayInPaymentMethod
import com.payabli.sdk.payin.model.PayInRequest
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * The form-to-transport boundary, measured rather than logged.
 *
 * A per-request log record identifies one outcome each and cannot answer latency, throughput, or approval and
 * refusal rates, which are the questions asked when a payment path is suspected.
 */
class PayInSubmissionTelemetryTest {
    /** Advances a millisecond per read, so a reported duration is non-zero. */
    private val ticks = AtomicLong(0)

    private val recorded = mutableListOf<Pair<String, Map<String, String>>>()

    @Before
    fun install() {
        TelemetryRecorders.install { event, properties -> recorded += event to properties }
    }

    @After
    fun clear() {
        TelemetryRecorders.clear()
    }

    @Test
    fun `an approved capture is reported under the capture name, timed`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission = submissionOver(FakePayInTransport.answering(APPROVED_TRANSACTION))

            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            val (event, properties) = recorded.single()
            assertEquals(TelemetryEvents.PAYIN_CAPTURE_COMPLETED, event)
            assertEquals(TelemetryProperties.Outcome.APPROVED, properties[TelemetryProperty.OUTCOME.key])
            assertTimed(properties)
        }

    @Test
    fun `an authorization is reported under its own name`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission = submissionOver(FakePayInTransport.answering(APPROVED_TRANSACTION))

            submission.submit(TEST_ENTRY_POINT, authorizeOf(), cardForm())

            assertEquals(TelemetryEvents.PAYIN_AUTHORIZE_COMPLETED, recorded.single().first)
        }

    @Test
    fun `storing a method is reported under its own name`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission = submissionOver(FakePayInTransport.answering(STORED_METHOD))

            submission.submit(TEST_ENTRY_POINT, PayabliPayInOperation.StoreMethod(), cardForm())

            assertEquals(TelemetryEvents.PAYIN_STORE_METHOD_COMPLETED, recorded.single().first)
        }

    @Test
    fun `a headless store is reported under the store name, timed`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission = submissionOver(FakePayInTransport.answering(STORED_METHOD))

            submission.storeMethod(TEST_ENTRY_POINT, cardStoreRequest())

            val (event, properties) = recorded.single()
            assertEquals(TelemetryEvents.PAYIN_STORE_METHOD_COMPLETED, event)
            assertEquals(TelemetryProperties.Outcome.APPROVED, properties[TelemetryProperty.OUTCOME.key])
            assertTimed(properties)
        }

    @Test
    fun `a form submission is reported as coming from the form`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission = submissionOver(FakePayInTransport.answering(STORED_METHOD))

            submission.submit(TEST_ENTRY_POINT, PayabliPayInOperation.StoreMethod(), cardForm())

            assertEquals(TelemetryProperties.Origin.FORM, recorded.single().second[TelemetryProperty.ORIGIN.key])
        }

    /** The same event name from both paths, so only this key tells a direct store from a form one. */
    @Test
    fun `every direct call is reported as direct`() =
        runTest(timeout = TEST_TIMEOUT) {
            submissionOver(FakePayInTransport.answering(STORED_METHOD))
                .storeMethod(TEST_ENTRY_POINT, cardStoreRequest())
            submissionOver(FakePayInTransport.answering(APPROVED_TRANSACTION)).capture(TEST_ENTRY_POINT, cardRequest())
            submissionOver(FakePayInTransport.answering(APPROVED_TRANSACTION))
                .void(TEST_ENTRY_POINT, "101-abc")

            assertEquals(3, recorded.size)
            recorded.forEach { (event, properties) ->
                assertEquals(event, TelemetryProperties.Origin.DIRECT, properties[TelemetryProperty.ORIGIN.key])
            }
        }

    /** Publishing to no state does not mean counting nothing: the boundary is still measured. */
    @Test
    fun `a void is reported under its own name, timed`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission = submissionOver(FakePayInTransport.answering(APPROVED_TRANSACTION))

            submission.void(TEST_ENTRY_POINT, "101-abc")

            val (event, properties) = recorded.single()
            assertEquals(TelemetryEvents.PAYIN_VOID_COMPLETED, event)
            assertEquals(TelemetryProperties.Outcome.APPROVED, properties[TelemetryProperty.OUTCOME.key])
            assertTimed(properties)
        }

    @Test
    fun `a read is reported under its own name, timed`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission = submissionOver(FakePayInTransport.answering(READ_RECORD))

            submission.readTransaction(TEST_ENTRY_POINT, "101-abc")

            val (event, properties) = recorded.single()
            assertEquals(TelemetryEvents.PAYIN_READ_COMPLETED, event)
            assertEquals(TelemetryProperties.Outcome.APPROVED, properties[TelemetryProperty.OUTCOME.key])
            assertEquals(TelemetryProperties.Origin.DIRECT, properties[TelemetryProperty.ORIGIN.key])
            assertTimed(properties)
        }

    @Test
    fun `a read that finds nothing carries its classification and no identifier`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission =
                submissionOver(
                    FakePayInTransport.answering("""{"isSuccess":false,"responseCode":7001}""", statusCode = 400),
                )

            submission.readTransaction(TEST_ENTRY_POINT, "101-abc")

            val (event, properties) = recorded.single()
            assertEquals(TelemetryEvents.PAYIN_READ_COMPLETED, event)
            assertEquals("TRANSACTION_NOT_FOUND", properties[TelemetryProperty.CODE.key])
            assertFalse(properties.values.any { it.contains("101-abc") })
        }

    @Test
    fun `a refused void carries the classification the caller was told`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission = submissionOver(FakePayInTransport.answering(DECLINED_TRANSACTION))

            submission.void(TEST_ENTRY_POINT, "101-abc")

            val (event, properties) = recorded.single()
            assertEquals(TelemetryEvents.PAYIN_VOID_COMPLETED, event)
            assertEquals(TelemetryProperties.Outcome.DECLINED, properties[TelemetryProperty.OUTCOME.key])
        }

    @Test
    fun `a decline is told apart from a failure, and carries no wording from the wire`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission = submissionOver(FakePayInTransport.answering(DECLINED_TRANSACTION))

            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            val properties = recorded.single().second
            assertEquals(TelemetryProperties.Outcome.DECLINED, properties[TelemetryProperty.OUTCOME.key])
            assertEquals("PAYMENT_DECLINED", properties[TelemetryProperty.CODE.key])
            assertEquals("1008", properties[TelemetryProperty.ERROR_NUMBER.key])
            assertEquals("Refused", properties[TelemetryProperty.FAILURE_TYPE.key])
        }

    /**
     * A failure this holder never classified is reported by the type the entry point caught, not by the
     * wrapper that replaced it.
     */
    @Test
    fun `an unexpected failure is reported by the type the entry point caught`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission =
                submissionOver(
                    FakePayInTransport.failingWith(IllegalStateException("a defect in a collaborator")),
                )

            submission.capture(TEST_ENTRY_POINT, cardRequest())

            val (event, properties) = recorded.single()
            assertEquals(TelemetryEvents.PAYIN_CAPTURE_COMPLETED, event)
            assertEquals(TelemetryProperties.Outcome.FAILED, properties[TelemetryProperty.OUTCOME.key])
            assertEquals("IllegalStateException", properties[TelemetryProperty.FAILURE_TYPE.key])
        }

    /** A form nobody can submit is a real thing to count, and it is invisible to anything watching requests. */
    @Test
    fun `a submission refused before anything is sent is reported as refused locally`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission = submissionOver(FakePayInTransport.answering(APPROVED_TRANSACTION))
            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())
            recorded.clear()

            // The first outcome has not been acknowledged, so the second submission is refused locally.
            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            val (event, properties) = recorded.single()
            assertEquals(TelemetryEvents.PAYIN_CAPTURE_COMPLETED, event)
            assertEquals(TelemetryProperties.Outcome.REFUSED_LOCALLY, properties[TelemetryProperty.OUTCOME.key])
            assertTrue(properties[TelemetryProperty.DURATION_MS.key] == null)
            assertEquals(TelemetryProperties.Origin.FORM, properties[TelemetryProperty.ORIGIN.key])
        }

    /** A payment the held key refuses before sending, either way, never reached the service. */
    @Test
    fun `a payment refused against a held key is reported as refused locally`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission =
                submissionOver(
                    FakePayInTransport.failingWith(PayabliGenericException(PayabliErrorType.NETWORK_ERROR, "dropped")),
                )
            submission.capture(TEST_ENTRY_POINT, cardRequest("order-1"))
            recorded.clear()

            submission.capture(TEST_ENTRY_POINT, cardRequest("order-1"))
            val otherPayment =
                PayInTransactionOptions(
                    PayInPaymentDetails(BigDecimal("99")),
                    paymentReference = "order-1",
                    attempt = PayabliAttempt.Retry,
                )
            submission.capture(TEST_ENTRY_POINT, PayInRequest(PayInPaymentMethod.Card(testCardData()), otherPayment))

            assertEquals(
                listOf(TelemetryProperties.Outcome.REFUSED_LOCALLY, TelemetryProperties.Outcome.REFUSED_LOCALLY),
                recorded.map { it.second[TelemetryProperty.OUTCOME.key] },
            )
            assertEquals(
                listOf(
                    PayabliErrorType.PAYMENT_REFERENCE_OPEN.wireName,
                    PayabliErrorType.RETRY_NOT_SAME_PAYMENT.wireName,
                ),
                recorded.map { it.second[TelemetryProperty.CODE.key] },
            )
            recorded.forEach { (_, properties) ->
                assertTrue(properties[TelemetryProperty.DURATION_MS.key] == null)
                assertEquals("PayabliGenericException", properties[TelemetryProperty.FAILURE_TYPE.key])
            }
        }

    /**
     * A form this SDK refuses before sending is refused locally, and there is nothing to time because no
     * request was made.
     */
    @Test
    fun `a form this SDK refuses before sending is reported as refused locally, untimed`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission = submissionOver(FakePayInTransport.answering(APPROVED_TRANSACTION))

            // An expiry that is not a month and a year is refused before any buffer is built.
            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm(expiry = "not-a-date"))

            val (event, properties) = recorded.single()
            assertEquals(TelemetryEvents.PAYIN_CAPTURE_COMPLETED, event)
            assertEquals(TelemetryProperties.Outcome.REFUSED_LOCALLY, properties[TelemetryProperty.OUTCOME.key])
            assertEquals(TelemetryProperties.Origin.FORM, properties[TelemetryProperty.ORIGIN.key])
            assertTrue(properties[TelemetryProperty.DURATION_MS.key] == null)
            assertEquals("InvalidInput", properties[TelemetryProperty.FAILURE_TYPE.key])
        }

    /**
     * The outcome is filed under the session the flow was built for.
     *
     * A capture that answers after a re-initialize is reported through the successor's channel, so without
     * the session travelling with the report the record carries the successor's identity.
     */
    @Test
    fun `an outcome is reported under the session the flow was built for`() =
        runTest(timeout = TEST_TIMEOUT) {
            val session = aTestSession()
            val seen = mutableListOf<TelemetrySessionContext>()
            TelemetryRecorders.install(
                object : TelemetryRecorder, SessionScopedRecorder {
                    override fun record(
                        event: String,
                        properties: Map<String, String>,
                    ) = Unit

                    override fun record(
                        event: String,
                        properties: Map<String, String>,
                        session: TelemetrySessionContext,
                    ) {
                        seen += session
                    }
                },
            )
            val submission = submissionOver(FakePayInTransport.answering(APPROVED_TRANSACTION), session)

            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            assertEquals(listOf(session), seen)
        }

    /**
     * The report names the entry point the request was sent to, not the session's.
     *
     * A flow takes its entry point independently of the session, so the two can differ. Reporting the
     * session's would file one merchant's payment under another, which is the same error as reading the
     * identity from the sending channel and arrives by a different route.
     */
    @Test
    fun `an outcome is reported under the entry point the request was sent to`() =
        runTest(timeout = TEST_TIMEOUT) {
            val seen = mutableListOf<TelemetrySessionContext>()
            TelemetryRecorders.install(
                object : TelemetryRecorder, SessionScopedRecorder {
                    override fun record(
                        event: String,
                        properties: Map<String, String>,
                    ) = Unit

                    override fun record(
                        event: String,
                        properties: Map<String, String>,
                        session: TelemetrySessionContext,
                    ) {
                        seen += session
                    }
                },
            )
            val submission = submissionOver(FakePayInTransport.answering(APPROVED_TRANSACTION), aTestSession())

            submission.submit("another-entry-point", captureOf(), cardForm())

            assertEquals("another-entry-point", seen.single().entryPoint)
            assertEquals(
                "the run is still the same run",
                "the-session-this-flow-was-built-for",
                seen.single().sessionId,
            )
        }

    /** And an authorized capture, which reads no form and reaches the same report by the same route. */
    @Test
    fun `an authorized capture is reported under the entry point it ran against`() =
        runTest(timeout = TEST_TIMEOUT) {
            val seen = mutableListOf<TelemetrySessionContext>()
            TelemetryRecorders.install(
                object : TelemetryRecorder, SessionScopedRecorder {
                    override fun record(
                        event: String,
                        properties: Map<String, String>,
                    ) = Unit

                    override fun record(
                        event: String,
                        properties: Map<String, String>,
                        session: TelemetrySessionContext,
                    ) {
                        seen += session
                    }
                },
            )
            val submission = submissionOver(FakePayInTransport.answering(APPROVED_TRANSACTION), aTestSession())

            submission.captureAuthorized(
                "another-entry-point",
                PayInAuthorizedRequest("101-abc", testDetails()),
            )

            assertEquals("another-entry-point", seen.single().entryPoint)
        }

    /**
     * A field the service rejects is the service refusing, and it is not the same number.
     *
     * `PayabliValidationException` carries `VALIDATION_ERROR`, and so does the exception this module raises
     * for a value it will not send, so a classification reading the code alone calls a spent request a local
     * refusal. What that ruins is the one comparison the pair exists for: how often this SDK refuses before
     * asking, against how often the service refuses when asked.
     */
    @Test
    fun `a field the service rejects is reported as refused rather than refused locally`() =
        runTest(timeout = TEST_TIMEOUT) {
            val refusal =
                PayabliValidationException(
                    httpStatus = 400,
                    fieldErrors = mapOf("accountNumber" to listOf(PayabliFieldError("refused"))),
                )
            val submission = submissionOver(FakePayInTransport.failingWith(refusal))

            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            val properties = recorded.single().second
            assertEquals(TelemetryProperties.Outcome.REFUSED, properties[TelemetryProperty.OUTCOME.key])
        }

    /**
     * An abandoned payment is the one outcome nobody is left to report, which is why it is reported here.
     *
     * The report lives in a `finally`, so it runs on the cancelled path as on any other. Nothing else covers
     * it: the cancellation tests install no recorder, so deleting that `finally` left the whole suite green
     * while the SDK stopped counting the payments a user walked away from.
     */
    @Test
    fun `an abandoned submission is reported as interrupted, and cancellation still propagates`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = GatedPayInTransport.answering(APPROVED_TRANSACTION)
            val submission = submissionOver(transport)

            val running =
                launch { submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm()) }
            transport.arrived.await()
            running.cancel()
            // join, not a scheduler advance: a cancelled coroutine completes when its finally has run, which
            // is where the report is.
            running.join()

            assertTrue("cancellation did not propagate", running.isCancelled)
            val (event, properties) = recorded.single()
            assertEquals(TelemetryEvents.PAYIN_CAPTURE_COMPLETED, event)
            assertEquals(
                TelemetryProperties.Outcome.INTERRUPTED,
                properties[TelemetryProperty.OUTCOME.key],
            )
            assertEquals(
                PayabliErrorType.USER_CANCELLED.wireName,
                properties[TelemetryProperty.CODE.key],
            )
            assertTrue(
                "an abandoned payment took some measurable time",
                properties[TelemetryProperty.DURATION_MS.key] != null,
            )
            assertTrue(
                "an abandoned payment names the cancellation this holder converted",
                properties[TelemetryProperty.FAILURE_TYPE.key] != null,
            )
        }

    /** A read a caller abandons is reported as interrupted, with the cancellation's type and a duration. */
    @Test
    fun `an abandoned read is reported as interrupted, and cancellation still propagates`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = GatedPayInTransport.answering(READ_RECORD)
            val submission = submissionOver(transport)

            val running = launch { submission.readTransaction(TEST_ENTRY_POINT, "101-abc") }
            transport.arrived.await()
            running.cancel()
            // join, not a scheduler advance: a cancelled coroutine completes when its report has run, which
            // is inside the catch that rethrows.
            running.join()

            assertTrue("cancellation did not propagate", running.isCancelled)
            val (event, properties) = recorded.single()
            assertEquals(TelemetryEvents.PAYIN_READ_COMPLETED, event)
            assertEquals(TelemetryProperties.Outcome.INTERRUPTED, properties[TelemetryProperty.OUTCOME.key])
            assertTrue(properties[TelemetryProperty.FAILURE_TYPE.key] != null)
            assertTrue(properties[TelemetryProperty.DURATION_MS.key] != null)
        }

    @Test
    fun `no instrument and no payer data reaches a report`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission = submissionOver(FakePayInTransport.answering(APPROVED_TRANSACTION))

            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            val reported = recorded.single().second
            assertEquals(
                setOf(TelemetryProperty.OUTCOME.key, TelemetryProperty.ORIGIN.key, TelemetryProperty.DURATION_MS.key),
                reported.keys,
            )
        }

    private fun TestScope.submissionOver(
        transport: PayabliTransport,
        session: TelemetrySessionContext? = null,
    ): PayInSubmission {
        val logger = RecordingSdkLogger()
        return PayInSubmission(
            moneyIn = MoneyInClient(transport, logger),
            storage = TokenStorageClient(transport, logger),
            dispatcher = StandardTestDispatcher(testScheduler),
            newIdempotencyKey = { "a-minted-key" },
            elapsedRealtimeNanos = { ticks.addAndGet(1_000_000) },
            logger = logger,
            session = session,
        )
    }

    /**
     * Asserts a duration was reported, and that it was measured on the clock this test injected.
     *
     * The bound is loose enough not to count how many times the code reads the clock, which is not what any
     * of these tests is about, and tight enough that a reading taken from any other clock fails it.
     */
    private fun assertTimed(properties: Map<String, String>) {
        val reported = properties[TelemetryProperty.DURATION_MS.key]?.toLongOrNull()
        assertTrue("not measured on the injected clock: $reported", reported != null && reported in 1..5)
    }

    private fun aTestSession() =
        TelemetrySessionContext(
            entryPoint = TEST_ENTRY_POINT,
            environment = PayabliEnvironment.SANDBOX,
            telemetryEnabled = true,
            sessionId = "the-session-this-flow-was-built-for",
            device = TelemetryDeviceContext.NONE,
        )

    private companion object {
        val TEST_TIMEOUT = 5.seconds
    }
}
