package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.payin.client.FakePayInTransport
import com.payabli.sdk.payin.client.MoneyInClient
import com.payabli.sdk.payin.client.RecordingLogger
import com.payabli.sdk.payin.client.TEST_ACCOUNT
import com.payabli.sdk.payin.client.TEST_EXPIRY_WIRE
import com.payabli.sdk.payin.client.TEST_PAN
import com.payabli.sdk.payin.client.TEST_SECURITY_CODE
import com.payabli.sdk.payin.client.TokenStorageClient
import com.payabli.sdk.payin.client.testDetails
import com.payabli.sdk.payin.form.PayInField
import com.payabli.sdk.payin.form.PayInFieldError
import com.payabli.sdk.payin.form.PayInFormValues
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * The submission holder: what it sends, what it reports, and what it leaves behind.
 *
 * Bounded, so a wedged coroutine fails at the assertion that was waiting.
 */
class PayInSubmissionTest {
    private val timeout = 5.seconds

    private val approved = APPROVED_TRANSACTION
    private val stored = STORED_METHOD
    private val declined = DECLINED_TRANSACTION

    /** A 400 in the shape ASP.NET model validation sends, which is the shape measured from the platform. */
    private val refusedCardNumber =
        """
        {"title":"Validation failed","errors":{"paymentMethod.cardnumber":["The card number is not valid."]}}
        """.trimIndent()

    // --- what reaches the wire ---

    @Test
    fun `a card capture sends the names the service reads and reports the transaction`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)
            val submission = submissionOver(transport)

            assertNotNull(submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm()))

            val body = transport.bodyText()
            assertTrue(body, body.contains(""""method":"card""""))
            assertTrue(body, body.contains(""""cardnumber":"$TEST_PAN""""))
            assertTrue(body, body.contains(""""cardexp":"$TEST_EXPIRY_WIRE""""))
            assertTrue(body, body.contains(""""cardcvv":"$TEST_SECURITY_CODE""""))
            assertTrue(body, body.contains(""""cardHolder":"Integration Test""""))
            assertTrue(body, body.contains(""""cardzip":"22039""""))

            val paid = succeededPayment(submission)
            assertEquals("A0000", paid.result.code)
            assertEquals("101-abc", paid.result.transaction?.paymentTransId)
        }

    @Test
    fun `a bank capture sends the ach names, and the choices the form leaves out take their defaults`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            assertNotNull(submissionOver(transport).submit(TEST_ENTRY_POINT, captureOf(), bankForm()))

            val body = transport.bodyText()
            assertTrue(body, body.contains(""""method":"ach""""))
            assertTrue(body, body.contains(""""achAccount":"$TEST_ACCOUNT""""))
            // Checking, because the service reads this field on every bank request.
            assertTrue(body, body.contains(""""achAccountType":"Checking""""))
            // WEB, which is what the service assumes, sent explicitly.
            assertTrue(body, body.contains(""""achCode":"WEB""""))
            // Absent, so the paypoint decides.
            assertFalse(body, body.contains("achHolderType"))
            assertFalse(body, body.contains(""""device""""))
        }

    @Test
    fun `the casing the form offers a choice in is not the casing the service reads`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            // The three values PayInStrings.choices puts behind those dropdowns.
            val values = bankForm(accountType = "Savings", holderType = "business", secCode = "web")
            assertNotNull(submissionOver(transport).submit(TEST_ENTRY_POINT, captureOf(), values))

            val body = transport.bodyText()
            assertTrue(body, body.contains(""""achAccountType":"Savings""""))
            assertTrue(body, body.contains(""""achHolderType":"business""""))
            assertTrue(body, body.contains(""""achCode":"WEB""""))
        }

    @Test
    fun `storing a method reports the identifier a later transaction charges`() =
        runTest(timeout = timeout) {
            val submission = submissionOver(FakePayInTransport.answering(stored))

            assertNotNull(submission.submit(TEST_ENTRY_POINT, PayabliPayInOperation.StoreMethod(), cardForm()))

            val state = submission.state.value
            assertTrue("$state", state is PayInSubmissionState.Succeeded.Method)
            assertEquals("tok-77", (state as PayInSubmissionState.Succeeded.Method).storedMethod.storedMethodId)
        }

    @Test
    fun `an authorization goes to the authorize route`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            assertNotNull(submissionOver(transport).submit(TEST_ENTRY_POINT, authorizeOf(), cardForm()))

            assertEquals("/api/v2/MoneyIn/authorize", transport.request?.path)
        }

    @Test
    fun `capturing an authorization reads no form and reports the transaction`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)
            val submission = submissionOver(transport)

            assertNotNull(submission.captureAuthorized(PayInAuthorizedRequest("101-abc", testDetails())))

            assertEquals("/api/v2/MoneyIn/capture/101-abc", transport.request?.path)
            assertEquals("A0000", succeededPayment(submission).result.code)
        }

    // --- one at a time ---

    @Test
    fun `a second submission while one is in flight is refused and sends nothing`() =
        runTest(timeout = timeout) {
            val transport = GatedPayInTransport.answering(approved)
            val submission = submissionOver(transport)

            val first = launch { submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm()) }
            transport.arrived.await()

            assertNull(
                "the second submission was accepted",
                submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm()),
            )
            assertEquals("a request reached the wire twice", 1, transport.sent.size)
            assertEquals(PayInSubmissionState.Submitting, submission.state.value)

            transport.release()
            first.join()
            assertTrue("${submission.state.value}", submission.state.value is PayInSubmissionState.Succeeded)
        }

    @Test
    fun `an outcome nobody acknowledged refuses the next submission, and acknowledging clears the way`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)
            val submission = submissionOver(transport)

            assertNotNull(submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm()))

            // The approval is still the state. Starting again would overwrite it with `Submitting`, and a
            // payment the service took would be left with nothing recording that it happened.
            assertNull(
                "a submission started over an outcome nobody had read",
                submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm()),
            )
            assertEquals("the refused submission reached the wire", 1, transport.count)

            assertTrue(submission.reset())
            assertNotNull("the guard was not released", submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm()))
        }

    @Test
    fun `reset returns to idle, and is refused while a submission is in flight`() =
        runTest(timeout = timeout) {
            val transport = GatedPayInTransport.answering(approved)
            val submission = submissionOver(transport)

            val running = launch { submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm()) }
            transport.arrived.await()
            assertFalse("idle was reported over a submission in flight", submission.reset())
            assertEquals(PayInSubmissionState.Submitting, submission.state.value)

            transport.release()
            running.join()
            assertTrue(submission.reset())
            assertEquals(PayInSubmissionState.Idle, submission.state.value)
        }

    // The buffers this layer builds are `PayInFormInstrumentTest`'s, which is the unit that owns them.

    @Test
    fun `nothing the payer typed for the instrument reaches the state or a log line`() =
        runTest(timeout = timeout) {
            val logger = RecordingLogger()
            val transport = FakePayInTransport.answering(declined)
            val submission =
                PayInSubmission(
                    moneyIn = MoneyInClient(transport, logger),
                    storage = TokenStorageClient(transport, logger),
                    dispatcher = StandardTestDispatcher(testScheduler),
                    newIdempotencyKey = { MINTED_KEY },
                    logger = logger,
                )

            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())
            val afterCard = submission.state.value
            submission.reset()
            submission.submit(TEST_ENTRY_POINT, PayabliPayInOperation.StoreMethod(), bankForm())
            val afterBank = submission.state.value

            val written =
                listOf(
                    logger.everythingWritten(),
                    afterCard.toString(),
                    afterBank.toString(),
                    failed(afterCard).cause.toString(),
                    failed(afterCard).cause.reason,
                ).joinToString(" ")
            listOf(TEST_PAN, TEST_SECURITY_CODE, TEST_ACCOUNT).forEach { secret ->
                assertFalse("a value the payer typed was written: $written", written.contains(secret))
            }
        }

    // --- what a failure says, and which field it says it about ---

    @Test
    fun `a decline arrives as a refusal, naming no field`() =
        runTest(timeout = timeout) {
            val submission = submissionOver(FakePayInTransport.answering(declined))

            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            val state = failed(submission.state.value)
            assertTrue("${state.cause}", state.cause is PayInException.Refused)
            assertEquals(PayabliErrorCode.PAYMENT_DECLINED, state.cause.code)
            assertEquals(emptyMap<PayInField, PayInFieldError>(), state.fieldErrors)
        }

    @Test
    fun `a validation refusal arrives attributed to the field the service named`() =
        runTest(timeout = timeout) {
            val submission = submissionOver(FakePayInTransport.answering(refusedCardNumber, statusCode = 400))

            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            val state = failed(submission.state.value)
            assertEquals(PayabliErrorCode.VALIDATION_ERROR, state.cause.code)
            assertEquals(mapOf(PayInField.CardNumber to PayInFieldError.NotAccepted), state.fieldErrors)
        }

    @Test
    fun `a choice this SDK does not offer is refused before anything is sent, naming that field`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)
            val submission = submissionOver(transport)

            submission.submit(TEST_ENTRY_POINT, captureOf(), bankForm(secCode = "iat"))

            assertNull("a request was sent for a form that was refused", transport.request)
            val state = failed(submission.state.value)
            assertTrue("${state.cause}", state.cause is PayInException.InvalidInput)
            assertEquals(mapOf(PayInField.SecCode to PayInFieldError.NotAccepted), state.fieldErrors)
        }

    @Test
    fun `an expiry the form never produced is refused, naming the expiry`() =
        runTest(timeout = timeout) {
            val submission = submissionOver(FakePayInTransport.answering(approved))

            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm(expiry = "not a month"))

            assertEquals(
                mapOf(PayInField.CardExpiration to PayInFieldError.NotAccepted),
                failed(submission.state.value).fieldErrors,
            )
        }

    @Test
    fun `an unexpected failure arrives classified, with its own message withheld`() =
        runTest(timeout = timeout) {
            // A message that quotes what it was given is what the redaction is for.
            val transport = FakePayInTransport.failingWith(IllegalStateException("could not parse $TEST_PAN"))
            val submission = submissionOver(transport)

            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            val state = failed(submission.state.value)
            assertEquals(PayabliErrorCode.UNKNOWN, state.cause.code)
            val carried =
                state.cause.cause
                    ?.message
                    .orEmpty()
            assertFalse("the cause carried its message: $carried", carried.contains(TEST_PAN))
            assertTrue("the cause lost the type that raised it: $carried", carried.contains("IllegalStateException"))
        }

    // --- cancellation ---

    @Test
    fun `a canceled submission records the key to retry with`() =
        runTest(timeout = timeout) {
            val transport = GatedPayInTransport.answering(approved)
            val submission = submissionOver(transport)

            val running =
                launch { submission.submit(TEST_ENTRY_POINT, captureOf(idempotencyKey = "key-9"), cardForm()) }
            transport.arrived.await()
            running.cancel()
            // join, not a scheduler advance: a canceled coroutine completes when its finally has run, which is
            // where the state write and the guard release are.
            running.join()

            val state = failed(submission.state.value)
            assertTrue("${state.cause}", state.cause is PayInException.Interrupted)
            assertEquals("key-9", state.retryKey)
            assertEquals(PayabliErrorCode.USER_CANCELLED, state.cause.code)
        }

    @Test
    fun `a caller who set no key still gets one to retry with`() =
        runTest(timeout = timeout) {
            // Without this the attempt is unrecoverable: a canceled capture may already have moved funds, and a
            // retry with no key can charge a second time.
            val transport = GatedPayInTransport.answering(approved)
            val submission = submissionOver(transport)

            val running = launch { submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm()) }
            transport.arrived.await()
            running.cancel()
            running.join()

            assertEquals("$MINTED_KEY-1", failed(submission.state.value).retryKey)
        }

    @Test
    fun `a network failure after the request was sent carries the key to retry with`() =
        runTest(timeout = timeout) {
            // Cancellation is not the only outcome that cannot say whether the service acted. A read that fails
            // once the bytes are written leaves the same question, and a resubmission with a fresh key charges
            // twice.
            val transport =
                FakePayInTransport.failingWith(
                    PayabliGenericException(PayabliErrorCode.NETWORK_ERROR, "the read timed out"),
                )
            val submission = submissionOver(transport)

            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            assertEquals("the request never reached the wire", 1, transport.count)
            assertEquals("$MINTED_KEY-1", failed(submission.state.value).retryKey)
        }

    @Test
    fun `a decline carries no key, because the service answered`() =
        runTest(timeout = timeout) {
            // A retry after a decline is a new attempt, and sending the first one's key would ask the service to
            // replay a refusal instead.
            val transport = FakePayInTransport.answering(declined)

            val submission = submissionOver(transport)
            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            assertNull(failed(submission.state.value).retryKey)
        }

    @Test
    fun `the minted key is sent, and a caller's own key is sent unchanged`() =
        runTest(timeout = timeout) {
            val minting = FakePayInTransport.answering(approved)
            submissionOver(minting).submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            assertEquals("$MINTED_KEY-1", minting.request?.headers?.get("idempotencyKey"))

            val supplied = FakePayInTransport.answering(approved)
            submissionOver(supplied).submit(TEST_ENTRY_POINT, captureOf(idempotencyKey = "key-9"), cardForm())

            assertEquals("key-9", supplied.request?.headers?.get("idempotencyKey"))
        }

    @Test
    fun `a second payment from one screen mints a second key`() =
        runTest(timeout = timeout) {
            // One key per attempt, not per holder: a resubmission the payer meant as a second payment must not
            // return the first one's result.
            val transport = FakePayInTransport.answering(approved)
            val submission = submissionOver(transport)

            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())
            val first = transport.request?.headers?.get("idempotencyKey")
            submission.reset()
            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            assertNotEquals(first, transport.request?.headers?.get("idempotencyKey"))
        }

    @Test
    fun `a canceled submission lets the cancellation through instead of answering its caller`() =
        runTest(timeout = timeout) {
            val transport = GatedPayInTransport.answering(approved)
            val submission = submissionOver(transport)

            // Completed only if submit returns, which is what a swallowed cancellation looks like from here.
            val answered = CompletableDeferred<PayInSubmissionState?>()
            val running = launch { answered.complete(submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())) }
            transport.arrived.await()
            running.cancel()
            running.join()

            assertFalse("submit answered a caller whose scope was already gone", answered.isCompleted)
            assertTrue("the coroutine was left running", running.isCancelled)
        }

    @Test
    fun `a submission is accepted after one was canceled`() =
        runTest(timeout = timeout) {
            val transport = GatedPayInTransport.answering(approved)
            val submission = submissionOver(transport)

            val running = launch { submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm()) }
            transport.arrived.await()
            running.cancel()
            // join, not a scheduler advance: a canceled coroutine completes when its finally has run, which is
            // where the state write and the guard release are.
            running.join()

            transport.release()
            // The interruption is an outcome like any other, and it names the key a retry needs, so it is
            // acknowledged before the next submission the same way an approval is.
            assertTrue("the interrupted outcome could not be acknowledged", submission.reset())
            assertNotNull(
                "the guard was held by a canceled submission",
                submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm()),
            )
        }

    @Test
    fun `a capture carries the customer the payer typed`() =
        runTest(timeout = timeout) {
            // The form collects these and the instrument does not hold them, so nothing else can carry them.
            // Sent with no customer, the QA paypoint answers 400 "Error in customer data".
            val transport = FakePayInTransport.answering(approved)
            val submission = submissionOver(transport)

            assertNotNull(submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm().withCustomer()))

            val body = transport.bodyText()
            assertTrue(body, body.contains(""""firstName":"Ada""""))
            assertTrue(body, body.contains(""""lastName":"Lovelace""""))
            assertTrue(body, body.contains(""""billingEmail":"ada@example.test""""))
            assertTrue(body, body.contains(""""billingZip":"90001""""))
        }

    @Test
    fun `a stored method carries the customer and the description the payer typed`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(stored)
            val submission = submissionOver(transport)

            val values = cardForm().withCustomer(methodDescription = "card on file")
            assertNotNull(submission.submit(TEST_ENTRY_POINT, PayabliPayInOperation.StoreMethod(), values))

            val body = transport.bodyText()
            assertTrue(body, body.contains(""""firstName":"Ada""""))
            assertTrue(body, body.contains(""""methodDescription":"card on file""""))
        }

    @Test
    fun `a form with no customer fields sends no customer at all`() =
        runTest(timeout = timeout) {
            // An empty customerData is a customer for the service to act on, so a form that collects none has
            // to leave the key out rather than send `{}`.
            val transport = FakePayInTransport.answering(approved)
            val submission = submissionOver(transport)

            assertNotNull(submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm()))

            assertTrue(transport.bodyText(), !transport.bodyText().contains("customerData"))
        }

    /** The same card values with the customer section filled in, which is what the demo's form collects. */
    private fun PayInFormValues.withCustomer(methodDescription: String? = null): PayInFormValues =
        PayInFormValues(
            method,
            values +
                buildMap {
                    put(PayInField.FirstName, "Ada")
                    put(PayInField.LastName, "Lovelace")
                    put(PayInField.BillingEmail, "ada@example.test")
                    put(PayInField.BillingPostalCode, "90001")
                    methodDescription?.let { put(PayInField.MethodDescription, it) }
                },
        )

    @Test
    fun `the outcome a collector sees can be acknowledged from inside the emission`() =
        runTest(timeout = timeout) {
            // A collector on `Unconfined` runs in the emitting thread's stack, so its `reset` lands while the
            // submission still holds its guard. The form consumes from exactly there.
            val submission = submissionOver(FakePayInTransport.answering(approved))
            var acknowledged: Boolean? = null

            val collector =
                launch(Dispatchers.Unconfined) {
                    submission.state.collect { state ->
                        if (state is PayInSubmissionState.Succeeded && acknowledged == null) {
                            acknowledged = submission.reset()
                        }
                    }
                }

            submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm())

            assertEquals(true, acknowledged)
            assertEquals(PayInSubmissionState.Idle, submission.state.value)
            collector.cancel()
        }

    @Test
    fun `a reset while a submission is in flight is refused, and cannot land between two of its steps`() =
        runTest(timeout = timeout) {
            // `reset` reads the guard and writes `Idle` under it, so a submit cannot take the guard between
            // the two and have its own `Submitting` overwritten.
            val transport = GatedPayInTransport.answering(approved)
            val submission = submissionOver(transport)

            val submitting = launch { submission.submit(TEST_ENTRY_POINT, captureOf(), cardForm()) }
            transport.arrived.await()

            assertFalse("the state was cleared under an active payment", submission.reset())
            assertEquals(PayInSubmissionState.Submitting, submission.state.value)

            transport.release()
            submitting.join()
            assertTrue("$submission", submission.state.value is PayInSubmissionState.Succeeded)
            assertTrue("a terminal state cannot be acknowledged", submission.reset())
            assertEquals(PayInSubmissionState.Idle, submission.state.value)
        }

    private fun TestScope.submissionOver(transport: PayabliTransport): PayInSubmission {
        val logger = RecordingLogger()
        return PayInSubmission(
            moneyIn = MoneyInClient(transport, logger),
            storage = TokenStorageClient(transport, logger),
            dispatcher = StandardTestDispatcher(testScheduler),
            // Counted, so a test can tell one minted key from the next without matching a UUID.
            newIdempotencyKey = { "$MINTED_KEY-${minted.incrementAndGet()}" },
            logger = logger,
        )
    }

    private val minted = AtomicInteger(0)

    private fun failed(state: PayInSubmissionState): PayInSubmissionState.Failed {
        assertTrue("expected a failure, and the state is $state", state is PayInSubmissionState.Failed)
        return state as PayInSubmissionState.Failed
    }

    private fun succeededPayment(submission: PayInSubmission): PayInSubmissionState.Succeeded.Payment {
        val state = submission.state.value
        assertTrue("expected a payment, and the state is $state", state is PayInSubmissionState.Succeeded.Payment)
        return state as PayInSubmissionState.Succeeded.Payment
    }

    private companion object {
        const val MINTED_KEY = "minted"
    }
}
