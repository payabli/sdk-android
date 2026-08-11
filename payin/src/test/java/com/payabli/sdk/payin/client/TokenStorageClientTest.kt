package com.payabli.sdk.payin.client

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.payin.model.PayInCustomerData
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInInstrument
import com.payabli.sdk.payin.model.PayInStoreOptions
import com.payabli.sdk.payin.model.PayInVendorData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Storing a method: the older envelope, and the one shape here whose field names come from the sibling
 * platform.
 */
class TokenStorageClientTest {
    private val timeout = 5.seconds

    private val stored =
        """
        {"isSuccess":true,"responseText":"Success",
         "responseData":{"referenceId":"tok-77","methodReferenceId":501,"customerId":88,
                         "resultCode":1,"resultText":"Approved"}}
        """.trimIndent()

    @Test
    fun `storing a card round-trips`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(stored)

            val result =
                TokenStorageClient(transport, RecordingLogger())
                    .storeMethod("merchant-entry", PayInInstrument.Card(testCard()))

            assertEquals("/api/TokenStorage/add", transport.request?.path)
            // Assembled as bytes, so the JSON content type is this client's to set.
            assertEquals("application/json", transport.request?.headers?.get("Content-Type"))
            val body = transport.bodyText()
            assertTrue(body, body.contains(""""cardnumber":"$TEST_PAN""""))
            assertTrue(body, body.contains(""""cardHolder":"Integration Test""""))
            assertTrue(body, body.contains(""""entryPoint":"merchant-entry""""))

            assertEquals("tok-77", result.storedMethodId)
            assertEquals(501L, result.methodReferenceId)
            assertEquals(88L, result.customerId)
            assertEquals(1, result.resultCode)
        }

    @Test
    fun `storing a bank account round-trips`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(stored)

            val result =
                TokenStorageClient(transport, RecordingLogger())
                    .storeMethod("e", PayInInstrument.BankAccount(testAccount()))

            assertTrue(transport.bodyText().contains(""""achAccount":"$TEST_ACCOUNT""""))
            assertEquals("tok-77", result.storedMethodId)
        }

    @Test
    fun `the four store flags are sent only when set`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(stored)

            TokenStorageClient(transport, RecordingLogger()).storeMethod(
                "e",
                PayInInstrument.Card(testCard()),
                PayInStoreOptions(achValidation = true, createAnonymous = false, temporary = true),
            )

            assertEquals(
                listOf("achValidation" to "true", "createAnonymous" to "false", "temporary" to "true"),
                transport.request?.query,
            )
        }

    @Test
    fun `no idempotency key is sent, because nothing reads one here`() =
        runTest(timeout = timeout) {
            // The service's idempotency middleware covers the MoneyIn paths only, so a key on this route is
            // read by nobody and the option does not exist.
            val transport = FakePayInTransport.answering(stored)

            TokenStorageClient(transport, RecordingLogger()).storeMethod("e", PayInInstrument.Card(testCard()))

            assertFalse(
                transport.request
                    ?.headers
                    .orEmpty()
                    .containsKey("idempotencyKey"),
            )
        }

    @Test
    fun `a refusal behind a 200 is a refusal`() =
        runTest(timeout = timeout) {
            // This route reports failure as isSuccess false with the status still 200. A client that read only
            // the status would report a stored method that was never stored.
            val refused =
                """
                {"isSuccess":false,"responseText":"Declined",
                 "responseData":{"resultCode":2,"resultText":"Card not supported"}}
                """.trimIndent()
            val transport = FakePayInTransport.answering(refused)

            val failure =
                runCatching {
                    TokenStorageClient(transport, RecordingLogger())
                        .storeMethod("e", PayInInstrument.Card(testCard()))
                }.exceptionOrNull()

            assertTrue(failure is PayInException.Refused)
            assertEquals("2", (failure as PayInException.Refused).failure.code)
            assertEquals("Card not supported", failure.failure.reason)
        }

    @Test
    fun `a result code of one approves even when isSuccess is absent`() =
        runTest(timeout = timeout) {
            val body = """{"responseData":{"referenceId":"tok-9","resultCode":1}}"""
            val transport = FakePayInTransport.answering(body)

            val result =
                TokenStorageClient(transport, RecordingLogger()).storeMethod("e", PayInInstrument.Card(testCard()))

            assertEquals("tok-9", result.storedMethodId)
        }

    @Test
    fun `a 200 that claims nothing is a refusal rather than a stored method`() =
        runTest(timeout = timeout) {
            // `{}` leaves isSuccess null and carries no result code. Reading that as success would report an
            // identifier-less stored method as a success the service never sent.
            val transport = FakePayInTransport.answering("{}")

            val failure =
                runCatching {
                    TokenStorageClient(transport, RecordingLogger())
                        .storeMethod("e", PayInInstrument.Card(testCard()))
                }.exceptionOrNull()

            assertTrue(failure is PayInException.Refused)
        }

    @Test
    fun `a body that will not decode is undecodable`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering("<html>bad gateway</html>")

            val failure =
                runCatching {
                    TokenStorageClient(transport, RecordingLogger())
                        .storeMethod("e", PayInInstrument.Card(testCard()))
                }.exceptionOrNull()

            assertTrue(failure is PayInException.Undecodable)
            assertEquals(PayabliErrorCode.DECODING_ERROR, (failure as PayInException).code)
        }

    @Test
    fun `a transport failure keeps its own classification`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering("""{"title":"Server error"}""", statusCode = 500)

            val failure =
                runCatching {
                    TokenStorageClient(transport, RecordingLogger())
                        .storeMethod("e", PayInInstrument.Card(testCard()))
                }.exceptionOrNull()

            assertEquals(PayabliErrorCode.SERVER_ERROR, (failure as? com.payabli.sdk.core.model.PayabliException)?.code)
        }

    @Test
    fun `the body bytes are overwritten once the call returns`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(stored)

            TokenStorageClient(transport, RecordingLogger()).storeMethod("e", PayInInstrument.Card(testCard()))

            assertTrue(transport.bodyReference!!.all { it == 0.toByte() })
        }

    @Test
    fun `the body bytes are overwritten when the transport throws`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.failingWith(java.io.IOException("connection reset"))

            runCatching {
                TokenStorageClient(transport, RecordingLogger())
                    .storeMethod("e", PayInInstrument.Card(testCard()))
            }

            assertTrue(transport.bodyReference!!.all { it == 0.toByte() })
        }

    @Test
    fun `storing sends the customer and vendor blocks`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(stored)
            val options =
                PayInStoreOptions(
                    customerData = PayInCustomerData(firstName = "Test", lastName = "User", customerNumber = "C-1"),
                    vendorData = PayInVendorData(vendorNumber = "V-1", name = "A Vendor", email = "v@example.com"),
                    methodDescription = "Main card",
                    fallbackAuth = true,
                    fallbackAuthAmount = 100,
                    source = "mobile",
                )

            TokenStorageClient(transport, RecordingLogger())
                .storeMethod("merchant-entry", PayInInstrument.Card(testCard()), options)

            val body = transport.bodyText()
            assertTrue(body, body.contains(""""firstName":"Test""""))
            assertTrue(body, body.contains(""""customerNumber":"C-1""""))
            assertTrue(body, body.contains(""""vendorNumber":"V-1""""))
            assertTrue(body, body.contains(""""name":"A Vendor""""))
            assertTrue(body, body.contains(""""methodDescription":"Main card""""))
            assertTrue(body, body.contains(""""fallbackAuth":true"""))
            assertTrue(body, body.contains(""""fallbackAuthAmount":100"""))
        }

    @Test
    fun `nothing sensitive reaches a log record`() =
        runTest(timeout = timeout) {
            val logger = RecordingLogger()

            TokenStorageClient(FakePayInTransport.answering(stored), logger)
                .storeMethod("e", PayInInstrument.Card(testCard()))

            val written = logger.everythingWritten()
            listOf(TEST_PAN, TEST_SECURITY_CODE, "Integration Test").forEach { value ->
                assertFalse(value, written.contains(value))
            }
        }
}
