package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.payin.client.FakePayInTransport
import com.payabli.sdk.payin.client.MoneyInClient
import com.payabli.sdk.payin.client.TokenStorageClient
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The form-to-transport boundary, measured rather than logged.
 *
 * A per-request log record identifies one outcome each and cannot answer latency, throughput, or approval and
 * refusal rates, which are the questions asked when a payment path is suspected.
 */
class PayInSubmissionTelemetryTest {
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
            assertNotNull(properties[TelemetryProperty.DURATION_MS.key]?.toLongOrNull())
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
    fun `a decline is told apart from a failure, and carries no wording from the wire`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission = submissionOver(FakePayInTransport.answering(DECLINED_TRANSACTION))

            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            val properties = recorded.single().second
            assertEquals(TelemetryProperties.Outcome.DECLINED, properties[TelemetryProperty.OUTCOME.key])
            assertEquals("PAYMENT_DECLINED", properties[TelemetryProperty.CODE.key])
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
                PayabliErrorCode.USER_CANCELLED.wireName,
                properties[TelemetryProperty.CODE.key],
            )
            assertTrue(
                "an abandoned payment took some measurable time",
                properties[TelemetryProperty.DURATION_MS.key] != null,
            )
        }

    @Test
    fun `no instrument and no payer data reaches a report`() =
        runTest(timeout = TEST_TIMEOUT) {
            val submission = submissionOver(FakePayInTransport.answering(APPROVED_TRANSACTION))

            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            val reported = recorded.single().second
            assertEquals(
                setOf(TelemetryProperty.OUTCOME.key, TelemetryProperty.DURATION_MS.key),
                reported.keys,
            )
        }

    private fun TestScope.submissionOver(transport: PayabliTransport): PayInSubmission {
        val logger = RecordingSdkLogger()
        return PayInSubmission(
            moneyIn = MoneyInClient(transport, logger),
            storage = TokenStorageClient(transport, logger),
            dispatcher = StandardTestDispatcher(testScheduler),
            newIdempotencyKey = { "a-minted-key" },
            logger = logger,
        )
    }

    private companion object {
        val TEST_TIMEOUT = 5.seconds
    }
}
