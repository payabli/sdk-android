package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.payin.client.FakePayInTransport
import com.payabli.sdk.payin.client.RecordingLogger
import com.payabli.sdk.payin.client.TEST_PAN
import com.payabli.sdk.payin.model.PayInException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The host-facing flow: what a caller gets back, and what it publishes while doing it.
 *
 * Built against a transport rather than a session, which is what the internal constructor is for: a
 * `PayabliSession` cannot be created from outside `:core`.
 */
class PayabliPayInPaymentFlowTest {
    private val timeout = 5.seconds

    @Test
    fun `a capture answers with the transaction the service approved`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.answering(APPROVED_TRANSACTION))

            val outcome = flow.capture(testOptions(), cardForm())

            assertEquals("A0000", outcome.getOrNull()?.code)
            assertTrue("${flow.state.value}", flow.state.value is PayInSubmissionState.Succeeded.Payment)
        }

    @Test
    fun `storing a method answers with the identifier a later transaction charges`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.answering(STORED_METHOD))

            val outcome = flow.storeMethod(cardForm())

            assertEquals("tok-77", outcome.getOrNull()?.storedMethodId)
        }

    @Test
    fun `a decline answers as a failure carrying the typed cause`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.answering(DECLINED_TRANSACTION))

            val outcome = flow.capture(testOptions(), cardForm())

            val cause = outcome.exceptionOrNull()
            assertTrue("$cause", cause is PayInException.Refused)
            assertEquals(PayabliErrorCode.PAYMENT_DECLINED, (cause as PayInException.Refused).code)
        }

    @Test
    fun `a second call while one is in flight answers that one already is`() =
        runTest(timeout = timeout) {
            val transport = GatedPayInTransport.answering(APPROVED_TRANSACTION)
            val flow = flowOver(transport)

            val first = launch { flow.capture(testOptions(), cardForm()) }
            transport.arrived.await()

            val refused = flow.capture(testOptions(), cardForm())

            assertTrue("$refused", refused.exceptionOrNull() is PayInException.AlreadySubmitting)
            assertEquals("a request reached the wire twice", 1, transport.sent.size)
            transport.release()
            first.join()
        }

    @Test
    fun `a tap while one is in flight is refused, having sent nothing`() =
        runTest(timeout = timeout) {
            val transport = GatedPayInTransport.answering(APPROVED_TRANSACTION)
            val flow = flowOver(transport)

            assertTrue("the first tap was refused", flow.start(captureOf(), cardForm()))
            transport.arrived.await()
            assertFalse("the second tap was accepted", flow.start(captureOf(), cardForm()))

            assertEquals(1, transport.sent.size)
            transport.release()
        }

    @Test
    fun `acknowledging an outcome returns to idle, and is refused while one is in flight`() =
        runTest(timeout = timeout) {
            val transport = GatedPayInTransport.answering(APPROVED_TRANSACTION)
            val flow = flowOver(transport)

            val running = launch { flow.capture(testOptions(), cardForm()) }
            transport.arrived.await()
            assertFalse("idle was reported over a submission in flight", flow.acknowledge())

            transport.release()
            running.join()
            assertTrue(flow.acknowledge())
            assertEquals(PayInSubmissionState.Idle, flow.state.value)
        }

    @Test
    fun `nothing the payer typed reaches the state a host reads`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.answering(APPROVED_TRANSACTION))

            flow.capture(testOptions(), cardForm())

            assertFalse(
                "${flow.state.value}",
                flow.state.value
                    .toString()
                    .contains(TEST_PAN),
            )
        }

    @Test
    fun `the state starts idle, before anything has been submitted`() =
        runTest(timeout = timeout) {
            assertEquals(
                PayInSubmissionState.Idle,
                flowOver(FakePayInTransport.answering(APPROVED_TRANSACTION)).state.value,
            )
            assertNull(FakePayInTransport.answering(APPROVED_TRANSACTION).request)
        }

    private fun TestScope.flowOver(transport: PayabliTransport): PayabliPayInPaymentFlow =
        PayabliPayInPaymentFlow(
            transport = transport,
            entryPoint = TEST_ENTRY_POINT,
            scope = this,
            dispatcher = StandardTestDispatcher(testScheduler),
            logger = RecordingLogger(),
        )
}
