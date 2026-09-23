package com.payabli.sdk.payin.payment

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.payin.PayInPaymentFlow
import com.payabli.sdk.payin.PayabliPayIn
import com.payabli.sdk.payin.client.FakePayInTransport
import com.payabli.sdk.payin.client.PayInRoutes
import com.payabli.sdk.payin.client.TEST_ACCOUNT
import com.payabli.sdk.payin.client.TEST_PAN
import com.payabli.sdk.payin.client.TEST_SECURITY_CODE
import com.payabli.sdk.payin.client.testAccount
import com.payabli.sdk.payin.client.testCard
import com.payabli.sdk.payin.client.testDetails
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInInstrument
import com.payabli.sdk.payin.model.PayInStoreRequest
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
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

    @Test
    fun `a host that collected the card captures without drawing a form`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(APPROVED_TRANSACTION)
            val flow: PayabliPayIn = flowOver(transport)

            val outcome = flow.capture(cardRequest())

            assertEquals("A0000", outcome.getOrNull()?.code)
            assertEquals("/api/v2/MoneyIn/getpaid", transport.request?.path)
            assertEquals(PayInSubmissionState.Idle, flow.state.value)
        }

    @Test
    fun `a host that collected the card authorizes without drawing a form`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(APPROVED_TRANSACTION)
            val flow: PayabliPayIn = flowOver(transport)

            val outcome = flow.authorize(cardRequest())

            assertEquals("A0000", outcome.getOrNull()?.code)
            assertEquals("/api/v2/MoneyIn/authorize", transport.request?.path)
            assertEquals(PayInSubmissionState.Idle, flow.state.value)
        }

    @Test
    fun `a host that collected the card stores it without drawing a form`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(STORED_METHOD)
            val flow: PayabliPayIn = flowOver(transport)

            val outcome = flow.storeMethod(cardStoreRequest())

            assertEquals("tok-77", outcome.getOrNull()?.storedMethodId)
            assertEquals(PayInRoutes.STORE_METHOD, transport.request?.path)
            assertEquals(PayInSubmissionState.Idle, flow.state.value)
        }

    @Test
    fun `a host that collected a bank account stores it without drawing a form`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(STORED_METHOD)
            val flow: PayabliPayIn = flowOver(transport)

            val outcome = flow.storeMethod(PayInStoreRequest(PayInInstrument.BankAccount(testAccount())))

            assertEquals("tok-77", outcome.getOrNull()?.storedMethodId)
            assertTrue(transport.bodyText(), transport.bodyText().contains(TEST_ACCOUNT))
        }

    @Test
    fun `a direct store sends no idempotency key`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(STORED_METHOD)

            flowOver(transport).storeMethod(cardStoreRequest())

            assertEquals(PayInRoutes.STORE_METHOD, transport.request?.path)
            assertNull(transport.sentKey())
        }

    @Test
    fun `a direct store leaves the caller's buffers intact`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(STORED_METHOD)
            val flow: PayabliPayIn = flowOver(transport)
            val cardData = testCardData()

            flow.storeMethod(cardStoreRequest(cardData))

            assertTrue(transport.bodyText(), transport.bodyText().contains(TEST_PAN))
            assertEquals(TEST_PAN.length, cardData.cardNumber.length)
            assertEquals(TEST_SECURITY_CODE.length, cardData.securityCode.length)

            cardData.cardNumber.close()
            cardData.securityCode.close()
            assertEquals(0, cardData.cardNumber.length)
            assertEquals(0, cardData.securityCode.length)
        }

    /** Storing moves no money, so a dropped link is the failure it is rather than an open outcome. */
    @Test
    fun `a direct store whose outcome is unknown is not reported as unsettled`() =
        runTest(timeout = timeout) {
            val flow: PayabliPayIn = flowOver(FakePayInTransport.failingWith(dropped()))

            val failure = flow.storeMethod(cardStoreRequest()).exceptionOrNull()

            assertEquals(PayabliErrorCode.NETWORK_ERROR, (failure as PayabliException).code)
            assertFalse("$failure", failure is PayInException.Unsettled)
        }

    @Test
    fun `a direct store of a card that fails its checksum sends nothing`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(STORED_METHOD)
            val flow: PayabliPayIn = flowOver(transport)

            val failure = flow.storeMethod(cardStoreRequest(testCard(pan = LUHN_FAILING_PAN))).exceptionOrNull()

            assertEquals(PayabliErrorCode.VALIDATION_ERROR, (failure as PayabliException).code)
            assertEquals(0, transport.count)
        }

    /**
     * The key the member's own documentation promises, on both money-moving members.
     *
     * Asserted on the header rather than on the request, because that is where a caller's key and a minted
     * one become the same thing. Without this the tests above pass with the reservation removed and the
     * payment sent under no key at all.
     */
    @Test
    fun `a direct call mints an idempotency key when the caller supplied none`() =
        runTest(timeout = timeout) {
            val captured = FakePayInTransport.answering(APPROVED_TRANSACTION)
            val authorized = FakePayInTransport.answering(APPROVED_TRANSACTION)

            flowOver(captured).capture(cardRequest())
            flowOver(authorized).authorize(cardRequest())

            assertNotNull("a capture went out under no key", captured.sentKey())
            assertNotNull("an authorization went out under no key", authorized.sentKey())
        }

    @Test
    fun `a direct call sends the caller's own key unchanged`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(APPROVED_TRANSACTION)

            flowOver(transport).capture(cardRequest(idempotencyKey = "key-9"))

            assertEquals("key-9", transport.sentKey())
        }

    /**
     * The buffers came from the caller, so the call does not close them.
     *
     * The form's own path builds the instrument per submission and closes it with the submission. A host
     * that built its own has to be able to close it when it decides to, and a member that closed it first
     * would leave a second call reading a wiped card.
     */
    @Test
    fun `a direct capture leaves the caller's buffers intact`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(APPROVED_TRANSACTION)
            val flow: PayabliPayIn = flowOver(transport)
            val cardData = testCardData()

            flow.capture(cardRequest(cardData = cardData))

            // The card reached the wire, so the buffer below survived a request that read it rather than a
            // call that never looked. Without this the assertions pass against a member that does nothing.
            assertEquals("/api/v2/MoneyIn/getpaid", transport.request?.path)
            assertTrue(transport.bodyText(), transport.bodyText().contains(TEST_PAN))

            // Both buffers, because a card carries two and wiping either one is the same defect.
            assertEquals(TEST_PAN.length, cardData.cardNumber.length)
            assertEquals(TEST_SECURITY_CODE.length, cardData.securityCode.length)

            // Still the caller's to close, and closing them still works.
            cardData.cardNumber.close()
            cardData.securityCode.close()
            assertEquals(0, cardData.cardNumber.length)
            assertEquals(0, cardData.securityCode.length)
        }

    /** The same ownership on the other money-moving member, which delegates separately and can regress alone. */
    @Test
    fun `a direct authorization leaves the caller's buffers intact`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(APPROVED_TRANSACTION)
            val flow: PayabliPayIn = flowOver(transport)
            val cardData = testCardData()

            flow.authorize(cardRequest(cardData = cardData))

            assertEquals("/api/v2/MoneyIn/authorize", transport.request?.path)
            assertTrue(transport.bodyText(), transport.bodyText().contains(TEST_PAN))

            assertEquals(TEST_PAN.length, cardData.cardNumber.length)
            assertEquals(TEST_SECURITY_CODE.length, cardData.securityCode.length)

            cardData.cardNumber.close()
            cardData.securityCode.close()
            assertEquals(0, cardData.cardNumber.length)
            assertEquals(0, cardData.securityCode.length)
        }

    /** A decline is an outcome the caller acts on, so it comes back rather than being thrown. */
    @Test
    fun `a declined direct capture answers as a failure carrying the typed cause`() =
        runTest(timeout = timeout) {
            val flow: PayabliPayIn = flowOver(FakePayInTransport.answering(DECLINED_TRANSACTION))

            val outcome = flow.capture(cardRequest())

            assertTrue("${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is PayInException.Refused)
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

    /** What the SDK keeps and what it reports agree, so a host is not told to start a new payment. */
    @Test
    fun `a refused repeat of this SDK's own key answers that the outcome is still open`() =
        runTest(timeout = timeout) {
            val transport =
                ScriptedPayInTransport(
                    listOf(
                        ScriptedPayInTransport.failingWith(dropped()),
                        ScriptedPayInTransport.answering(409, "Duplicated idempotencyKey"),
                    ),
                )
            val flow = flowOver(transport)
            val request = PayInAuthorizedRequest("101-abc", testDetails())

            flow.captureAuthorizedTransaction(request)
            val cause = flow.captureAuthorizedTransaction(request).exceptionOrNull()

            assertTrue("$cause", cause is PayInException.Unsettled)
            assertEquals(PayabliErrorCode.CONFLICT, (cause as PayInException.Unsettled).code)
        }

    /** The SDK is still holding the key, so a refusal of the send cannot be reported as an answer. */
    @Test
    fun `a rate limit on a resent key answers that the outcome is still open`() =
        runTest(timeout = timeout) {
            val transport =
                ScriptedPayInTransport(
                    listOf(
                        ScriptedPayInTransport.failingWith(dropped()),
                        ScriptedPayInTransport.answering(429),
                    ),
                )
            val flow = flowOver(transport)
            val request = PayInAuthorizedRequest("101-abc", testDetails())

            flow.captureAuthorizedTransaction(request)
            val first = transport.request?.headers?.get("idempotencyKey")
            val cause = flow.captureAuthorizedTransaction(request).exceptionOrNull()

            // The second send carries the first key, which is what makes this the resent case rather than
            // a first attempt that happened to be rate limited.
            assertEquals(first, transport.request?.headers?.get("idempotencyKey"))
            assertTrue("$cause", cause is PayInException.Unsettled)
            assertEquals(PayabliErrorCode.RATE_LIMITED, (cause as PayInException.Unsettled).code)
        }

    /** Nothing holds a key for the form, so naming one would point at a key that does not exist. */
    @Test
    fun `a rate limit on a form submission is not an open outcome`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.answering("", statusCode = 429))

            val cause = flow.capture(testOptions(), cardForm()).exceptionOrNull()

            assertFalse("$cause", cause is PayInException.Unsettled)
        }

    /** A settled refusal is itself, so a caller branching on the type is not told to wait and see. */
    @Test
    fun `a decline is not reported as an open outcome`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.answering(DECLINED_TRANSACTION))

            val cause = flow.voidTransaction("101-abc").exceptionOrNull()

            assertFalse("$cause", cause is PayInException.Unsettled)
        }

    /** The message can quote a response body, so the message goes and the type stays. */
    @Test
    fun `the reported failure withholds what the underlying one said, and names what raised it`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.failingWith(dropped()))

            val cause = flow.voidTransaction("101-abc").exceptionOrNull() as PayInException.Unsettled

            assertFalse("${cause.cause}", cause.cause?.message?.contains(DROPPED_DETAIL) == true)
            assertTrue("${cause.cause}", cause.cause?.message?.contains("PayabliGenericException") == true)
        }

    /**
     * The same handle on the path that returns a `Result`, which never had it. A caller told to reconcile
     * and handed no identifier has been given an instruction it cannot carry out.
     */
    @Test
    fun `a result that leaves the outcome open still names the transaction to reconcile`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.answering(SERVICE_ERROR_NAMING_TRANSACTION))

            val cause = flow.voidTransaction("101-abc").exceptionOrNull() as PayInException.Unsettled

            assertEquals("101-abc", cause.paymentTransId)
        }

    /**
     * A redaction happens once. Wrapping one that has already happened names this SDK's own stand-in and
     * carries the frames of the site that built it, so the type that failed is gone from what a host reads.
     */
    @Test
    fun `an unexpected failure keeps the type that raised it through the wrapper`() =
        runTest(timeout = timeout) {
            val raised = IllegalStateException("could not parse $TEST_PAN")
            val flow = flowOver(FakePayInTransport.failingWith(raised))

            val cause = flow.voidTransaction("101-abc").exceptionOrNull() as PayInException.Unsettled

            assertTrue("${cause.cause}", cause.cause?.message?.contains("IllegalStateException") == true)
            assertFalse("${cause.cause}", cause.cause?.message?.contains(TEST_PAN) == true)
        }

    /**
     * The two facts are separable, so the form reads each from its own place: whether the outcome is
     * unknown from the cause, and whether there is a key to resend from `retryKey`.
     */
    @Test
    fun `the state a form reads carries both the unknown outcome and the key`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.failingWith(dropped()))

            val cause = flow.capture(testOptions(), cardForm()).exceptionOrNull()
            val published = flow.state.value as PayInSubmissionState.Failed

            assertTrue("$cause", cause is PayInException.Unsettled)
            assertTrue("${published.cause}", published.cause is PayInException.Unsettled)
            assertEquals(PayabliErrorCode.NETWORK_ERROR, published.cause.code)
            assertNotNull("the form's own channel still carries the key", published.retryKey)
        }

    /**
     * A `409` establishes that a request carrying that key got past the check, and nothing about whether
     * the payment was taken. Rotating on it starts a new payment for one that may already have been made.
     */
    @Test
    fun `a conflict on a form submission answers that the outcome is unknown, carrying no key`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.answering("Duplicated idempotencyKey", statusCode = 409))

            val cause = flow.capture(testOptions(), cardForm()).exceptionOrNull()
            val published = flow.state.value as PayInSubmissionState.Failed

            assertTrue("$cause", cause is PayInException.Unsettled)
            assertEquals(PayabliErrorCode.CONFLICT, (cause as PayInException.Unsettled).code)
            assertTrue("${published.cause}", published.cause is PayInException.Unsettled)
            assertNull("a host following the contract would resubmit this key", published.retryKey)
        }

    /** Whoever chose the key, the service's answer says the same thing about the payment. */
    @Test
    fun `a conflict on the caller's own key answers that the outcome is unknown`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.answering("Duplicated idempotencyKey", statusCode = 409))

            val cause =
                flow
                    .captureAuthorizedTransaction(
                        PayInAuthorizedRequest("101-abc", testDetails(), idempotencyKey = "caller-chose-this"),
                    ).exceptionOrNull()

            assertTrue("$cause", cause is PayInException.Unsettled)
            assertEquals(PayabliErrorCode.CONFLICT, (cause as PayInException.Unsettled).code)
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

    /**
     * The holder being full does not change which failure a caller receives.
     *
     * Whether there was room to keep a key is a fact about a map on the device. It does not make an unknown
     * outcome known, and the episode that fills the holder is the one where payments are most likely to be
     * unresolved at once.
     */
    @Test
    fun `an unknown outcome past the holder's capacity is still reported as unknown`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.failingWith(dropped())
            val flow = flowOver(transport)
            repeat(PayInSubmission.HELD_KEYS_MAX) {
                flow.captureAuthorizedTransaction(PayInAuthorizedRequest("101-$it", testDetails()))
            }

            val overflowing = PayInAuthorizedRequest("101-past-the-cap", testDetails())
            val past = flow.captureAuthorizedTransaction(overflowing)
            val firstKey = transport.request?.headers?.get("idempotencyKey")
            flow.captureAuthorizedTransaction(overflowing)

            val failure = past.exceptionOrNull()
            assertTrue("$failure", failure is PayInException.Unsettled)
            // The premise as well as the subject. Held, the repeat would resend the same key and the
            // assertion above would hold whether the cap had fired or not.
            assertNotEquals(
                "a key was retained past the cap",
                firstKey,
                transport.request?.headers?.get("idempotencyKey"),
            )
        }

    /** A store is settled by reading the entry point's methods back, so it never reports an open outcome. */
    @Test
    fun `a store whose outcome is unknown is not reported as unsettled`() =
        runTest(timeout = timeout) {
            val flow = flowOver(FakePayInTransport.failingWith(dropped()))

            val failure = flow.storeMethod(cardForm()).exceptionOrNull()

            assertFalse("$failure", failure is PayInException.Unsettled)
        }

    @Test
    fun `the declared store member refuses in the error taxonomy and sends nothing`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(STORED_METHOD)
            val flow: PayabliPayIn = flowOver(transport)

            val failure = flow.storeMethod(PayInStoreRequest(PayInInstrument.Card(testCardData()))).exceptionOrNull()

            assertEquals(PayabliErrorCode.UNKNOWN, (failure as PayabliException).code)
            assertEquals(0, transport.count)
        }

    private fun dropped(): PayabliGenericException =
        PayabliGenericException(PayabliErrorCode.NETWORK_ERROR, DROPPED_DETAIL)

    private fun FakePayInTransport.sentKey(): String? = request?.headers?.get(PayInRoutes.HEADER_IDEMPOTENCY_KEY)

    private fun TestScope.flowOver(transport: PayabliTransport): PayInPaymentFlow =
        PayInPaymentFlow.over(
            transport = transport,
            entryPoint = TEST_ENTRY_POINT,
            scope = this,
            dispatcher = StandardTestDispatcher(testScheduler),
            elapsedRealtimeNanos = { 0 },
            logger = RecordingSdkLogger(),
        )

    private companion object {
        /** Stands in for text a real failure would carry from the wire, so a test can assert it is withheld. */
        const val DROPPED_DETAIL = "the link dropped"

        /** Sixteen digits whose Luhn check fails. */
        const val LUHN_FAILING_PAN = "4111111111111112"
    }
}
