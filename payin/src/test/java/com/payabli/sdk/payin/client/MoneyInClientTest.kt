package com.payabli.sdk.payin.client

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliValidationException
import com.payabli.sdk.payin.form.ExpiryValue
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInCustomerData
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInPaymentMethod
import com.payabli.sdk.payin.model.PayInRequest
import com.payabli.sdk.payin.model.PayInTransactionOptions
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

/**
 * The transaction routes: what goes out, what comes back, and what a refusal is told apart from.
 *
 * Bounded, so a wedged coroutine fails at the assertion that was waiting.
 */
class MoneyInClientTest {
    private val timeout = 5.seconds

    private val approved =
        """
        {"code":"A0000","reason":"Approved","explanation":"Transaction approved","action":"none",
         "data":{"paymentTransId":"101-abc","gatewayTransId":"gtw-9","orderId":"order-1","method":"card",
                 "transStatus":1,"paypointId":42,"totalAmount":10.00,"netAmount":9.71,
                 "connectorName":"fiserv","payorId":7}}
        """.trimIndent()

    private fun cardRequest(
        method: PayInPaymentMethod = PayInPaymentMethod.Card(testCard()),
        idempotencyKey: String? = null,
        validationCode: String? = null,
        achValidation: Boolean? = null,
        sameDayAch: Boolean? = null,
        isAsync: Boolean? = null,
        useCaching: Boolean? = null,
        forceCustomerCreation: Boolean? = null,
    ) = PayInRequest(
        paymentMethod = method,
        options =
            PayInTransactionOptions(
                paymentDetails = testDetails(),
                idempotencyKey = idempotencyKey,
                validationCode = validationCode,
                achValidation = achValidation,
                sameDayAch = sameDayAch,
                isAsync = isAsync,
                useCaching = useCaching,
                forceCustomerCreation = forceCustomerCreation,
            ),
    )

    @Test
    fun `a capture round-trips, sending every field the service reads`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            val result = MoneyInClient(transport, RecordingSdkLogger()).capture("merchant-entry", cardRequest())

            assertEquals("/api/v2/MoneyIn/getpaid", transport.request?.path)
            val body = transport.bodyText()
            // The casing is the service's own.
            assertTrue(body, body.contains(""""method":"card""""))
            assertTrue(body, body.contains(""""cardnumber":"$TEST_PAN""""))
            assertTrue(body, body.contains(""""cardexp":"$TEST_EXPIRY_WIRE""""))
            assertTrue(body, body.contains(""""cardcvv":"$TEST_SECURITY_CODE""""))
            assertTrue(body, body.contains(""""cardHolder":"Integration Test""""))
            assertTrue(body, body.contains(""""cardzip":"22039""""))
            assertTrue(body, body.contains(""""entryPoint":"merchant-entry""""))
            // Unquoted, two decimal places, as the service's own payloads write it.
            assertTrue(body, body.contains(""""totalAmount":10.00"""))

            assertEquals("A0000", result.code)
            // The three words beside the code, which the envelope carries on every approval and this client
            // decoded and discarded until they were published.
            assertEquals("Approved", result.reason)
            assertEquals("Transaction approved", result.explanation)
            assertEquals("none", result.action)
            assertEquals("101-abc", result.transaction?.paymentTransId)
            assertEquals(BigDecimal("10.00"), result.transaction?.totalAmount)
            assertEquals(42L, result.transaction?.paypointId)
            assertEquals(7L, result.transaction?.customerId)
        }

    @Test
    fun `a bank account sends the ach field names and defaults the authorization`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingSdkLogger())
                .capture("merchant-entry", cardRequest(PayInPaymentMethod.BankAccount(testAccount())))

            val body = transport.bodyText()
            assertTrue(body, body.contains(""""method":"ach""""))
            assertTrue(body, body.contains(""""achAccount":"$TEST_ACCOUNT""""))
            assertTrue(body, body.contains(""""achRouting":"$TEST_ROUTING""""))
            // Capitalised, unlike achHolderType. The service's own payloads confirm it.
            assertTrue(body, body.contains(""""achAccountType":"Checking""""))
            assertTrue(body, body.contains(""""achHolder":"Integration Test""""))
            // The default is sent, so the request states which authorization applies.
            assertTrue(body, body.contains(""""achCode":"WEB""""))
        }

    private suspend fun refusal(block: suspend () -> Unit): PayInException.InvalidInput? =
        runCatching { block() }.exceptionOrNull() as? PayInException.InvalidInput

    @Test
    fun `the idempotency key and the validation code are headers, spelled as the service reads them`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingSdkLogger())
                .capture("e", cardRequest(idempotencyKey = "key-1", validationCode = "code-9"))

            assertEquals("key-1", transport.request?.headers?.get("idempotencyKey"))
            assertEquals("code-9", transport.request?.headers?.get("validationCode"))
            // Only the caller's own headers are here. `Authorization` and the JSON content type are chain
            // steps applied inside the transport, so an undecorated request carries neither.
            assertEquals(setOf("idempotencyKey", "validationCode"), transport.request?.headers?.keys)
        }

    @Test
    fun `a header value carrying a line break is refused, naming the header`() =
        runTest(timeout = timeout) {
            // `setRequestProperty` throws `IllegalArgumentException` on a line break, which reaches a caller
            // as an unchecked exception out of the transport. This is the refusal it can handle instead.
            val transport = FakePayInTransport.answering(approved)

            val failure =
                refusal {
                    MoneyInClient(transport, RecordingSdkLogger())
                        .capture("e", cardRequest(idempotencyKey = "key-1\r\nX-Injected: v"))
                }

            assertEquals("idempotencyKey", failure?.field)
            // Refused before the request was built, so nothing was sent at all.
            assertNull(transport.request)
        }

    @Test
    fun `a validation code carrying a control character is refused the same way`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            val failure =
                refusal {
                    MoneyInClient(transport, RecordingSdkLogger())
                        .capture("e", cardRequest(validationCode = "code\u0000nine"))
                }

            assertEquals("validationCode", failure?.field)
        }

    @Test
    fun `surrounding whitespace is still trimmed rather than refused`() =
        runTest(timeout = timeout) {
            // The check looks at what trimming leaves, so an ordinary padded value keeps working.
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingSdkLogger()).capture("e", cardRequest(idempotencyKey = "  key-1  "))

            assertEquals("key-1", transport.request?.headers?.get("idempotencyKey"))
        }

    @Test
    fun `a blank idempotency key is refused rather than quietly dropped`() =
        runTest(timeout = timeout) {
            // Setting a key is what makes a caller's retry safe, so dropping " " would send an unprotected
            // capture to a caller who believes it is protected and can be charged twice after a lost response.
            val transport = FakePayInTransport.answering(approved)

            val failure =
                refusal {
                    MoneyInClient(transport, RecordingSdkLogger()).capture("e", cardRequest(idempotencyKey = " "))
                }

            assertEquals("idempotencyKey", failure?.field)
            assertNull(transport.request)
        }

    @Test
    fun `no idempotency key at all is still allowed`() =
        runTest(timeout = timeout) {
            // null is how a caller says it did not set one, and stays the only way to say it.
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingSdkLogger()).capture("e", cardRequest(idempotencyKey = null))

            assertNull(transport.request?.headers?.get("idempotencyKey"))
        }

    @Test
    fun `the shared card fixture cannot expire with the calendar`() {
        // The clients validate against ExpiryValue.today() with no clock to inject, so a fixture written as a
        // fixed year is a date on which this whole package turns red without a change having been made.
        val today = ExpiryValue.today()

        assertFalse(TEST_EXPIRY.isExpired(today.year, today.month))
        assertTrue("$TEST_EXPIRY is not far enough ahead", TEST_EXPIRY.year > today.year + 1)
    }

    @Test
    fun `an authorization carries the key too`() =
        runTest(timeout = timeout) {
            // The same request type serves both calls, and an authorization repeated without a key places a
            // second hold on the payer's funds.
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingSdkLogger()).authorize("e", cardRequest(idempotencyKey = "key-1"))

            assertEquals("key-1", transport.request?.headers?.get("idempotencyKey"))
        }

    @Test
    fun `only the flags that were set are sent`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingSdkLogger())
                .capture("e", cardRequest(achValidation = true, sameDayAch = false, isAsync = true))

            val query = transport.request?.query.orEmpty()
            assertEquals(
                listOf("achValidation" to "true", "sameDayACH" to "false", "isAsync" to "true"),
                query,
            )
            // An absent flag is not a false one: the paypoint's own default decides.
            assertFalse(query.any { it.first == "useCaching" })
            assertFalse(query.any { it.first == "forceCustomerCreation" })
        }

    @Test
    fun `authorize omits achValidation, which that route does not take`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingSdkLogger()).authorize("e", cardRequest(achValidation = true))

            assertEquals("/api/v2/MoneyIn/authorize", transport.request?.path)
            assertFalse(
                transport.request
                    ?.query
                    .orEmpty()
                    .any { it.first == "achValidation" },
            )
        }

    @Test
    fun `authorize refuses everything but entered card details, before transport`() =
        runTest(timeout = timeout) {
            val methods =
                listOf(
                    PayInPaymentMethod.BankAccount(testAccount()),
                    PayInPaymentMethod.Stored("stored-1"),
                    PayInPaymentMethod.CloudDevice("device-1"),
                    PayInPaymentMethod.Check("A Payer"),
                    PayInPaymentMethod.Cash,
                )

            methods.forEach { method ->
                val transport = FakePayInTransport.answering(approved)
                val failure =
                    runCatching { MoneyInClient(transport, RecordingSdkLogger()).authorize("e", cardRequest(method)) }
                        .exceptionOrNull()

                assertTrue(method.toString(), failure is PayInException.InvalidInput)
                // Refused before the request was built, so nothing reached the transport.
                assertNull(method.toString(), transport.request)
            }
        }

    @Test
    fun `capturing an authorization resolves the path and logs the template`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingSdkLogger())
                .captureAuthorized(PayInAuthorizedRequest("101-abc", testDetails("4.50")))

            assertEquals("/api/v2/MoneyIn/capture/101-abc", transport.request?.path)
            // The template, because a resolved path embeds an identifier and is never loggable.
            assertEquals("/api/v2/MoneyIn/capture/{transId}", transport.request?.route)
            assertTrue(transport.bodyText().contains(""""totalAmount":4.50"""))
        }

    @Test
    fun `capturing an authorization carries an idempotency key when one is given`() =
        runTest(timeout = timeout) {
            // Under the same middleware as a capture, and it moves money: without a key a lost response
            // cannot be retried without risking a second partial capture.
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingSdkLogger())
                .captureAuthorized(PayInAuthorizedRequest("101-abc", testDetails("4.50"), idempotencyKey = "key-2"))

            assertEquals("key-2", transport.request?.headers?.get("idempotencyKey"))
        }

    @Test
    fun `capturing an authorization sends no key when none is given`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingSdkLogger())
                .captureAuthorized(PayInAuthorizedRequest("101-abc", testDetails("4.50")))

            assertFalse(
                transport.request
                    ?.headers
                    .orEmpty()
                    .containsKey("idempotencyKey"),
            )
        }

    @Test
    fun `voiding resolves the path, logs the template and sends no body`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingSdkLogger()).void("101-abc")

            assertEquals("/api/v2/MoneyIn/void/101-abc", transport.request?.path)
            // The template, because a resolved path embeds an identifier and is never loggable.
            assertEquals("/api/v2/MoneyIn/void/{transId}", transport.request?.route)
            // The route takes nothing but the identifier, so a body would be a field the service never reads.
            assertNull(transport.request?.body)
        }

    @Test
    fun `voiding carries an idempotency key when one is given`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingSdkLogger()).void("101-abc", idempotencyKey = "key-3")

            assertEquals("key-3", transport.request?.headers?.get("idempotencyKey"))
        }

    @Test
    fun `voiding sends no key when none is given`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingSdkLogger()).void("101-abc")

            assertFalse(
                transport.request
                    ?.headers
                    .orEmpty()
                    .containsKey("idempotencyKey"),
            )
        }

    @Test
    fun `voiding refuses a blank transaction id before anything is sent`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            val failure =
                runCatching { MoneyInClient(transport, RecordingSdkLogger()).void("  ") }
                    .exceptionOrNull() as? PayInException.InvalidInput

            assertEquals("transId", failure?.field)
            assertNull(transport.request)
        }

    /**
     * The state the service will not reverse, which is the answer this SDK passes through rather than
     * predicting. A 400 is a validation refusal whatever the route, so the caller reads the service's own
     * words off the typed failure.
     */
    @Test
    fun `a refused void arrives as a typed failure carrying what the service said`() =
        runTest(timeout = timeout) {
            val transport =
                FakePayInTransport.answering(
                    """{"title":"Invalid transaction status","detail":"The status of the transaction """ +
                        """does not allow the action requested.","code":"E7002",""" +
                        """"errors":{"transId":["Invalid transaction status"]}}""",
                    statusCode = 400,
                )

            val failure =
                runCatching { MoneyInClient(transport, RecordingSdkLogger()).void("101-abc") }
                    .exceptionOrNull() as? PayabliValidationException

            assertEquals("Invalid transaction status", failure?.reason)
            assertEquals("E7002", failure?.rawCode)
            assertEquals(400, failure?.httpStatus)
        }

    @Test
    fun `a declined code behind a 200 is a refusal, not a result`() =
        runTest(timeout = timeout) {
            val declined =
                """{"code":"D0329","reason":"Insufficient funds","explanation":"Card has no funds","action":"r"}"""
            val transport = FakePayInTransport.answering(declined)

            val failure =
                runCatching { MoneyInClient(transport, RecordingSdkLogger()).capture("e", cardRequest()) }
                    .exceptionOrNull()

            assertTrue(failure is PayInException.Refused)
            val refused = failure as PayInException.Refused
            assertEquals("D0329", refused.failure.code)
            assertEquals("Insufficient funds", refused.failure.reason)
            assertEquals(PayabliErrorCode.PAYMENT_DECLINED, refused.code)
        }

    @Test
    fun `an error code is not a decline`() =
        runTest(timeout = timeout) {
            // A `D` is the payer's card; an `E` is the service. Reporting the second as the first puts decline
            // wording in front of a payer whose card was never asked.
            val body = """{"code":"E4001","reason":"Processor unavailable","explanation":"Try later","action":"r"}"""
            val transport = FakePayInTransport.answering(body)

            val failure =
                runCatching { MoneyInClient(transport, RecordingSdkLogger()).capture("e", cardRequest()) }
                    .exceptionOrNull()

            assertTrue(failure is PayInException.ServiceError)
            val error = failure as PayInException.ServiceError
            assertEquals("E4001", error.failure.code)
            assertEquals(PayabliErrorCode.SERVER_ERROR, error.code)
        }

    @Test
    fun `a decline names the transaction the service made`() =
        runTest(timeout = timeout) {
            // A refused transaction still exists at the paypoint, and this is the only handle a caller has
            // for reconciling, voiding or logging it.
            val declined =
                """{"code":"D0329","reason":"Insufficient funds","data":{"paymentTransId":"101-abc"}}"""
            val transport = FakePayInTransport.answering(declined)

            val failure =
                runCatching { MoneyInClient(transport, RecordingSdkLogger()).capture("e", cardRequest()) }
                    .exceptionOrNull()

            assertEquals("101-abc", (failure as PayInException.Refused).failure.paymentTransId)
        }

    @Test
    fun `a service error names it too`() =
        runTest(timeout = timeout) {
            val body = """{"code":"E4001","reason":"Processor unavailable","data":{"paymentTransId":"101-def"}}"""
            val transport = FakePayInTransport.answering(body)

            val failure =
                runCatching { MoneyInClient(transport, RecordingSdkLogger()).capture("e", cardRequest()) }
                    .exceptionOrNull()

            assertEquals("101-def", (failure as PayInException.ServiceError).failure.paymentTransId)
        }

    @Test
    fun `a refusal that named no transaction invents none`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering("""{"code":"D0329","reason":"Insufficient funds"}""")

            val failure =
                runCatching { MoneyInClient(transport, RecordingSdkLogger()).capture("e", cardRequest()) }
                    .exceptionOrNull()

            assertNull((failure as PayInException.Refused).failure.paymentTransId)
        }

    @Test
    fun `a code family this SDK does not know is not a decline either`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering("""{"code":"X9999","reason":"Something else"}""")

            val failure =
                runCatching { MoneyInClient(transport, RecordingSdkLogger()).capture("e", cardRequest()) }
                    .exceptionOrNull()

            assertTrue(failure is PayInException.ServiceError)
        }

    @Test
    fun `an approval on a 201 is still an approval`() =
        runTest(timeout = timeout) {
            // The service returns Created for a v2 success unless its own table says otherwise, so a client
            // asserting 200 would refuse the ordinary case.
            val transport = FakePayInTransport.answering(approved, statusCode = 201)

            val result = MoneyInClient(transport, RecordingSdkLogger()).capture("e", cardRequest())

            assertEquals("A0000", result.code)
        }

    @Test
    fun `a 2xx that will not decode is undecodable, not a refusal`() =
        runTest(timeout = timeout) {
            // "The service said no" and "this SDK cannot read what the service said" call for different
            // actions, and collapsing them reports an integration fault to a payer as a decline.
            listOf("{}", "<html>502</html>", """{"reason":"no code here"}""").forEach { body ->
                val transport = FakePayInTransport.answering(body)
                val failure =
                    runCatching { MoneyInClient(transport, RecordingSdkLogger()).capture("e", cardRequest()) }
                        .exceptionOrNull()

                assertTrue(body, failure is PayInException.Undecodable)
                assertEquals(body, PayabliErrorCode.DECODING_ERROR, (failure as PayInException).code)
            }
        }

    @Test
    fun `a 401 reaches the caller as an expired token, whatever the body says`() =
        runTest(timeout = timeout) {
            // Behind a session this never runs: the authenticated transport consumes a 401, refreshes, replays,
            // and throws on the second one rather than returning it. What a bare transport does with a 401 is
            // asserted here so the mapping is not mistaken for something this client decides.
            val transport = FakePayInTransport.answering("", statusCode = 401)

            val failure =
                runCatching { MoneyInClient(transport, RecordingSdkLogger()).capture("e", cardRequest()) }
                    .exceptionOrNull()

            assertEquals(
                PayabliErrorCode.TOKEN_EXPIRED,
                (failure as? com.payabli.sdk.core.model.PayabliException)?.code,
            )
        }

    @Test
    fun `the body bytes are overwritten once the call returns`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingSdkLogger()).capture("e", cardRequest())

            // The same array the client handed the transport, which it wipes afterwards rather than before:
            // credential recovery may replay the request inside the transport and needs the bytes intact.
            assertTrue(transport.bodyReference!!.all { it == 0.toByte() })
        }

    @Test
    fun `the body bytes are overwritten when the transport throws`() =
        runTest(timeout = timeout) {
            // The wipe lives in a finally, and a test that only ever sees a successful response would pass
            // just as well if it did not.
            val transport = FakePayInTransport.failingWith(java.io.IOException("connection reset"))

            val failure =
                runCatching { MoneyInClient(transport, RecordingSdkLogger()).capture("e", cardRequest()) }
                    .exceptionOrNull()

            assertTrue(failure is java.io.IOException)
            assertTrue(transport.bodyReference!!.all { it == 0.toByte() })
        }

    @Test
    fun `a capture sends the customer block under the names the service reads`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)
            val request =
                PayInRequest(
                    paymentMethod = PayInPaymentMethod.Card(testCard()),
                    options =
                        PayInTransactionOptions(
                            paymentDetails = testDetails(fee = "1.50"),
                            customerData =
                                PayInCustomerData(
                                    firstName = "Test",
                                    lastName = "User",
                                    billingEmail = "test@example.com",
                                    billingAddress1 = "123 Test St",
                                    billingCity = "Test City",
                                    billingState = "CA",
                                    billingZip = "90001",
                                    billingCountry = "US",
                                    // Blank is the same statement as absent, and the service defaults some.
                                    company = "  ",
                                    additionalData = mapOf("invoice" to "INV-1"),
                                ),
                            orderId = "order-1",
                            orderDescription = "Two things",
                            ipAddress = "203.0.113.4",
                            subscriptionId = 9L,
                        ),
                )

            MoneyInClient(transport, RecordingSdkLogger()).capture("merchant-entry", request)

            val body = transport.bodyText()
            assertTrue(body, body.contains(""""firstName":"Test""""))
            assertTrue(body, body.contains(""""billingEmail":"test@example.com""""))
            assertTrue(body, body.contains(""""billingZip":"90001""""))
            assertTrue(body, body.contains(""""additionalData":{"invoice":"INV-1"}"""))
            // Lower case, which is the service's spelling for this one.
            assertTrue(body, body.contains(""""ipaddress":"203.0.113.4""""))
            assertTrue(body, body.contains(""""orderDescription":"Two things""""))
            assertTrue(body, body.contains(""""subscriptionId":9"""))
            assertTrue(body, body.contains(""""serviceFee":1.50"""))
            assertFalse(body, body.contains("company"))
        }

    @Test
    fun `nothing sensitive reaches a log record`() =
        runTest(timeout = timeout) {
            val logger = RecordingSdkLogger()

            MoneyInClient(FakePayInTransport.answering(approved), logger).capture("e", cardRequest())

            val written = logger.everythingWritten()
            listOf(TEST_PAN, TEST_SECURITY_CODE, TEST_EXPIRY_WIRE, "Integration Test").forEach { value ->
                assertFalse(value, written.contains(value))
            }
        }

    @Test
    fun `a refusal's reason is not logged either`() =
        runTest(timeout = timeout) {
            // A refusal message can quote what was submitted, so the reason is displayable and never
            // loggable.
            val echoing = "Card 4111111111111111 was refused"
            val declined = """{"code":"D0001","reason":"$echoing"}"""
            val logger = RecordingSdkLogger()

            runCatching { MoneyInClient(FakePayInTransport.answering(declined), logger).capture("e", cardRequest()) }

            assertFalse(logger.everythingWritten().contains("4111111111111111"))
        }
}
