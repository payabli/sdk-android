package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.payin.PayInPaymentFlow
import com.payabli.sdk.payin.PayabliPayIn
import com.payabli.sdk.payin.client.FakePayInTransport
import com.payabli.sdk.payin.client.TEST_PAN
import com.payabli.sdk.payin.client.testDetails
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class PayInPaymentFlowTest {
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
    fun `an authorization answers with the transaction, having placed a hold`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(APPROVED_TRANSACTION)
            val flow = flowOver(transport)

            val outcome = flow.authorize(testOptions(), cardForm())

            assertEquals("A0000", outcome.getOrNull()?.code)
            assertEquals("/api/v2/MoneyIn/authorize", transport.request?.path)
        }

    @Test
    fun `capturing an authorization answers with the transaction and reads no form`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(APPROVED_TRANSACTION)
            val flow: PayabliPayIn = flowOver(transport)

            val outcome = flow.captureAuthorizedTransaction(PayInAuthorizedRequest("101-abc", testDetails()))

            assertEquals("A0000", outcome.getOrNull()?.code)
            assertEquals("/api/v2/MoneyIn/capture/101-abc", transport.request?.path)
        }

    @Test
    fun `voiding answers with the transaction and reads no form`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(APPROVED_TRANSACTION)
            val flow: PayabliPayIn = flowOver(transport)

            val outcome = flow.voidTransaction("101-abc")

            assertEquals("A0000", outcome.getOrNull()?.code)
            assertEquals("/api/v2/MoneyIn/void/101-abc", transport.request?.path)
        }

    /**
     * The reason the two calls above publish nothing to [PayabliPayIn.state].
     *
     * A terminal state stands until the form consumes it, and nothing draws these, so a first call that
     * published would leave the second refused with no public way to clear it. Asserted through the
     * interface, because that is the surface a host holds.
     */
    @Test
    fun `two calls in a row both reach the wire`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(APPROVED_TRANSACTION)
            val flow: PayabliPayIn = flowOver(transport)

            val first = flow.captureAuthorizedTransaction(PayInAuthorizedRequest("101-abc", testDetails()))
            val second = flow.voidTransaction("101-abc")

            assertEquals("A0000", first.getOrNull()?.code)
            assertEquals("A0000", second.getOrNull()?.code)
            assertEquals(2, transport.count)
            assertEquals("/api/v2/MoneyIn/void/101-abc", transport.request?.path)
        }

    /** Neither call is the form's, so neither moves the state the form renders. */
    @Test
    fun `a void leaves the form's state alone`() =
        runTest(timeout = timeout) {
            val flow: PayabliPayIn = flowOver(FakePayInTransport.answering(APPROVED_TRANSACTION))

            flow.voidTransaction("101-abc")

            assertEquals(PayInSubmissionState.Idle, flow.state.value)
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

    /**
     * What a caller holding a `Result` gets is that the outcome is open, and the classification underneath.
     *
     * Not the key. It is held for this payment and resent by the next call naming the same transaction, so
     * there is nothing for a caller to carry; `PayInSubmissionTest` covers that the resend reuses it.
     */
    @Test
    fun `capturing an authorization answers that the outcome is open, carrying no key`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.failingWith(dropped()))

            val cause =
                flow
                    .captureAuthorizedTransaction(PayInAuthorizedRequest("101-abc", testDetails()))
                    .exceptionOrNull()

            assertTrue("$cause", cause is PayInException.Unsettled)
            assertEquals(PayabliErrorCode.NETWORK_ERROR, (cause as PayInException.Unsettled).code)
        }

    @Test
    fun `voiding answers that the outcome is open`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.failingWith(dropped()))

            val cause = flow.voidTransaction("101-abc").exceptionOrNull()

            assertTrue("$cause", cause is PayInException.Unsettled)
        }

    /** A settled refusal is itself, so a caller branching on the type is not told to wait and see. */
    @Test
    fun `a decline is not reported as an open outcome`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.answering(DECLINED_TRANSACTION))

            val cause = flow.voidTransaction("101-abc").exceptionOrNull()

            assertFalse("$cause", cause is PayInException.Unsettled)
        }

    /** The message can quote a response body, so only the type survives. */
    @Test
    fun `the reported failure withholds what the underlying one said`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.failingWith(dropped()))

            val cause = flow.voidTransaction("101-abc").exceptionOrNull() as PayInException.Unsettled

            assertTrue("${cause.cause}", cause.cause?.message?.contains("PayabliGenericException") == true)
            assertFalse("${cause.cause}", cause.cause?.message?.contains(DROPPED_DETAIL) == true)
        }

    /**
     * The form reads `PayInSubmissionState.Failed.retryKey`, so wrapping there would be a second channel for
     * one key and would change what a host catches.
     */
    @Test
    fun `the state a form reads carries the underlying failure, not the wrapper`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.failingWith(dropped()))

            val cause = flow.capture(testOptions(), cardForm()).exceptionOrNull()
            val published = flow.state.value as PayInSubmissionState.Failed

            assertTrue("$cause", cause is PayInException.Unsettled)
            assertFalse("${published.cause}", published.cause is PayInException.Unsettled)
            assertNotNull("the form's own channel still carries the key", published.retryKey)
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
            assertFalse("idle was reported over a submission in flight", flow.consume())

            transport.release()
            running.join()
            assertTrue(flow.consume())
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

    @Test
    fun `the second start is refused without waiting for a dispatch`() =
        runTest(timeout = timeout) {
            // Two forms share one flow whenever a host mounts the sheet over the inline one. Launched
            // dispatched, `start` returned before `Submitting` was published, so both callers were told their
            // submission was accepted and both then waited on one outcome.
            val transport = GatedPayInTransport.answering(APPROVED_TRANSACTION)
            val flow = flowOver(transport)

            val first = flow.start(captureOf(), cardForm())
            val second = flow.start(captureOf(), cardForm())

            assertTrue("the first start was refused", first)
            assertFalse("a second submission was accepted", second)
            assertEquals(PayInSubmissionState.Submitting, flow.state.value)

            transport.arrived.await()
            transport.release()
            assertEquals("more than one request reached the wire", 1, transport.sent.size)
        }

    @Test
    fun `a tap inside the terminal emission is refused, because the guard still holds`() =
        runTest(timeout = timeout) {
            // The outcome is published while the single flight is still held. A collector on `Unconfined` runs in
            // the emitting thread's stack, so its tap lands in that window: the state no longer reads
            // `Submitting`, and only the guard can say the submission was refused. Told it was accepted, the form
            // waits for an outcome nothing will publish.
            val transport = FakePayInTransport.answering(APPROVED_TRANSACTION)
            val flow = flowOver(transport)
            var secondTap: Boolean? = null

            val collector =
                launch(Dispatchers.Unconfined) {
                    flow.state.collect { state ->
                        if (state is PayInSubmissionState.Succeeded && secondTap == null) {
                            secondTap = flow.start(captureOf(), cardForm())
                        }
                    }
                }

            flow.capture(testOptions(), cardForm())

            assertEquals(false, secondTap)
            assertEquals("a second request reached the wire", 1, transport.count)
            collector.cancel()
        }

    private fun dropped(): PayabliGenericException =
        PayabliGenericException(PayabliErrorCode.NETWORK_ERROR, DROPPED_DETAIL)

    private fun TestScope.flowOver(transport: PayabliTransport): PayInPaymentFlow =
        PayInPaymentFlow.over(
            transport = transport,
            entryPoint = TEST_ENTRY_POINT,
            scope = this,
            dispatcher = StandardTestDispatcher(testScheduler),
            logger = RecordingSdkLogger(),
        )

    private companion object {
        /** Stands in for text a real failure would carry from the wire, so a test can assert it is withheld. */
        const val DROPPED_DETAIL = "the link dropped"
    }
}
