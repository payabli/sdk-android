package com.payabli.sdk.payin.client

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliValidationException
import com.payabli.sdk.payin.model.PayInAuthorizedRequest
import com.payabli.sdk.payin.model.PayInCustomerData
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInPaymentMethod
import com.payabli.sdk.payin.model.PayInRequest
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
        paymentDetails = testDetails(),
        paymentMethod = method,
        idempotencyKey = idempotencyKey,
        validationCode = validationCode,
        achValidation = achValidation,
        sameDayAch = sameDayAch,
        isAsync = isAsync,
        useCaching = useCaching,
        forceCustomerCreation = forceCustomerCreation,
    )

    @Test
    fun `a capture round-trips, sending every field the service reads`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            val result = MoneyInClient(transport, RecordingLogger()).capture("merchant-entry", cardRequest())

            assertEquals("/api/v2/MoneyIn/getpaid", transport.request?.path)
            val body = transport.bodyText()
            // The casing is the service's own.
            assertTrue(body, body.contains(""""method":"card""""))
            assertTrue(body, body.contains(""""cardnumber":"$TEST_PAN""""))
            assertTrue(body, body.contains(""""cardexp":"12/30""""))
            assertTrue(body, body.contains(""""cardcvv":"$TEST_SECURITY_CODE""""))
            assertTrue(body, body.contains(""""cardHolder":"Integration Test""""))
            assertTrue(body, body.contains(""""cardzip":"22039""""))
            assertTrue(body, body.contains(""""entryPoint":"merchant-entry""""))
            // Unquoted, two decimal places, as the service's own payloads write it.
            assertTrue(body, body.contains(""""totalAmount":10.00"""))

            assertEquals("A0000", result.code)
            assertEquals("101-abc", result.transaction?.paymentTransId)
            assertEquals(BigDecimal("10.00"), result.transaction?.totalAmount)
            assertEquals(7L, result.transaction?.customerId)
        }

    @Test
    fun `a bank account sends the ach field names and defaults the authorisation`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingLogger())
                .capture("merchant-entry", cardRequest(PayInPaymentMethod.BankAccount(testAccount())))

            val body = transport.bodyText()
            assertTrue(body, body.contains(""""method":"ach""""))
            assertTrue(body, body.contains(""""achAccount":"$TEST_ACCOUNT""""))
            assertTrue(body, body.contains(""""achRouting":"$TEST_ROUTING""""))
            // Capitalised, unlike achHolderType. The service's own payloads confirm it.
            assertTrue(body, body.contains(""""achAccountType":"Checking""""))
            assertTrue(body, body.contains(""""achHolder":"Integration Test""""))
            // The default is sent, so the request states which authorisation applies.
            assertTrue(body, body.contains(""""achCode":"WEB""""))
        }

    @Test
    fun `the idempotency key and the validation code are headers, spelled as the service reads them`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingLogger())
                .capture("e", cardRequest(idempotencyKey = "key-1", validationCode = "code-9"))

            assertEquals("key-1", transport.request?.headers?.get("idempotencyKey"))
            assertEquals("code-9", transport.request?.headers?.get("validationCode"))
        }

    @Test
    fun `only the flags that were set are sent`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingLogger())
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

            MoneyInClient(transport, RecordingLogger()).authorize("e", cardRequest(achValidation = true))

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
                    runCatching { MoneyInClient(transport, RecordingLogger()).authorize("e", cardRequest(method)) }
                        .exceptionOrNull()

                assertTrue(method.toString(), failure is PayInException.InvalidInput)
                // Refused before the request was built, so nothing reached the transport.
                assertNull(method.toString(), transport.request)
            }
        }

    @Test
    fun `capturing an authorisation resolves the path and logs the template`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(approved)

            MoneyInClient(transport, RecordingLogger())
                .captureAuthorized(PayInAuthorizedRequest("101-abc", testDetails("4.50")))

            assertEquals("/api/v2/MoneyIn/capture/101-abc", transport.request?.path)
            // The template, because a resolved path embeds an identifier and is never loggable.
            assertEquals("/api/v2/MoneyIn/capture/{transId}", transport.request?.route)
            assertTrue(transport.bodyText().contains(""""totalAmount":4.50"""))
        }

    @Test
    fun `a declined code behind a 200 is a refusal, not a result`() =
        runTest(timeout = timeout) {
            val declined =
                """{"code":"D0329","reason":"Insufficient funds","explanation":"Card has no funds","action":"r"}"""
            val transport = FakePayInTransport.answering(declined)

            val failure =
                runCatching { MoneyInClient(transport, RecordingLogger()).capture("e", cardRequest()) }
                    .exceptionOrNull()

            assertTrue(failure is PayInException.Refused)
            val refused = failure as PayInException.Refused
            assertEquals("D0329", refused.failure.code)
            assertEquals("Insufficient funds", refused.failure.reason)
            assertEquals(PayabliErrorCode.PAYMENT_DECLINED, refused.code)
        }

    @Test
    fun `an approval on a 201 is still an approval`() =
        runTest(timeout = timeout) {
            // The service returns Created for a v2 success unless its own table says otherwise, so a client
            // asserting 200 would refuse the ordinary case.
            val transport = FakePayInTransport.answering(approved, statusCode = 201)

            val result = MoneyInClient(transport, RecordingLogger()).capture("e", cardRequest())

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
                    runCatching { MoneyInClient(transport, RecordingLogger()).capture("e", cardRequest()) }
                        .exceptionOrNull()

                assertTrue(body, failure is PayInException.Undecodable)
                assertEquals(body, PayabliErrorCode.DECODING_ERROR, (failure as PayInException).code)
            }
        }

    @Test
    fun `an input problem the service reports as 401 keeps its field names`() =
        runTest(timeout = timeout) {
            // The service decides a v2 error's status from a lookup table rather than at the call site, so an
            // input problem arrives as 401. Left as `:core` maps it, a host app would re-initialise a session
            // that was never the problem.
            val body =
                """
                {"status":401,"title":"One or more validation errors occurred.","code":"E9001",
                 "errors":{"entryPoint":[{"message":"The entry point was not recognised",
                                          "suggestion":"Check the entry point"}]}}
                """.trimIndent()
            val transport = FakePayInTransport.answering(body, statusCode = 401)

            val failure =
                runCatching { MoneyInClient(transport, RecordingLogger()).capture("e", cardRequest()) }
                    .exceptionOrNull()

            assertTrue(failure is PayabliValidationException)
            val validation = failure as PayabliValidationException
            assertEquals(PayabliErrorCode.VALIDATION_ERROR, validation.code)
            // The status the service actually sent, not the 400 the body was re-read under.
            assertEquals(401, validation.httpStatus)
            assertEquals("E9001", validation.rawCode)
            assertEquals(
                "The entry point was not recognised",
                validation.fieldErrors
                    .getValue("entryPoint")
                    .single()
                    .message,
            )
        }

    @Test
    fun `a 401 carrying no problem document is still a credential rejection`() =
        runTest(timeout = timeout) {
            // Only an input problem dressed as a 401 is reclassified. A bare 401 is what it says it is.
            val transport = FakePayInTransport.answering("", statusCode = 401)

            val failure =
                runCatching { MoneyInClient(transport, RecordingLogger()).capture("e", cardRequest()) }
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

            MoneyInClient(transport, RecordingLogger()).capture("e", cardRequest())

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
                runCatching { MoneyInClient(transport, RecordingLogger()).capture("e", cardRequest()) }
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
                    paymentDetails = testDetails(fee = "1.50"),
                    paymentMethod = PayInPaymentMethod.Card(testCard()),
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
                            // Blank is the same statement as absent, and the service defaults some of these.
                            company = "  ",
                            additionalData = mapOf("invoice" to "INV-1"),
                        ),
                    orderId = "order-1",
                    orderDescription = "Two things",
                    ipAddress = "203.0.113.4",
                    subscriptionId = 9L,
                )

            MoneyInClient(transport, RecordingLogger()).capture("merchant-entry", request)

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
            val logger = RecordingLogger()

            MoneyInClient(FakePayInTransport.answering(approved), logger).capture("e", cardRequest())

            val written = logger.everythingWritten()
            listOf(TEST_PAN, TEST_SECURITY_CODE, "12/30", "Integration Test").forEach { value ->
                assertFalse(value, written.contains(value))
            }
        }

    @Test
    fun `a refusal's reason is not logged either`() =
        runTest(timeout = timeout) {
            // The service echoes submitted values into some of these messages, so the reason is displayable
            // and never loggable.
            val echoing = "Card 4111111111111111 was refused"
            val declined = """{"code":"D0001","reason":"$echoing"}"""
            val logger = RecordingLogger()

            runCatching { MoneyInClient(FakePayInTransport.answering(declined), logger).capture("e", cardRequest()) }

            assertFalse(logger.everythingWritten().contains("4111111111111111"))
        }
}
