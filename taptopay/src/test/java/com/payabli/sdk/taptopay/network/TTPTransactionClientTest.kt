package com.payabli.sdk.taptopay.network

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.taptopay.model.TapToPayCustomerData
import com.payabli.sdk.taptopay.model.TapToPayInvoiceData
import com.payabli.sdk.taptopay.model.TapToPayPaymentDetails
import com.payabli.sdk.taptopay.network.FakeTransactionTransport.Companion.answer
import com.payabli.sdk.taptopay.provider.CardReadRequest
import com.payabli.sdk.taptopay.provider.FakeTapToPayProvider
import com.payabli.sdk.taptopay.provider.cardRead
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

private const val ENTRY = "merchant-entry"
private const val DEVICE = "poi-1"
private const val TRANS_ID = "12-abc"

private const val KEY = "an-attempt-key"

/**
 * Field names a card-present transaction may be recorded under. Anything else is a leak waiting to happen.
 *
 * The last five are `Retry`'s, not this client's: closing a transaction hands it this same logger, so its
 * records are part of what this class emits and are asserted here rather than taken on trust.
 */
private val ALLOWED_LOG_FIELDS =
    setOf(
        "event",
        "route",
        "statusCode",
        "contentLength",
        "errorCode",
        "attempt",
        "maxAttempts",
        "retryable",
        "retryAfter",
        "timeoutMs",
    )

class TTPTransactionClientTest {
    private val timeout = 5.seconds
    private val logger = RecordingSdkLogger()

    private fun client(vararg answers: com.payabli.sdk.core.network.PayabliResponse) =
        FakeTransactionTransport(*answers).let { it to TTPTransactionClient(it, logger) }

    private suspend fun TTPTransactionClient.open(
        entryPoint: String = ENTRY,
        deviceId: String = DEVICE,
        details: TapToPayPaymentDetails = TapToPayPaymentDetails(BigDecimal("10")),
        idempotencyKey: String = KEY,
        customer: TapToPayCustomerData = TapToPayCustomerData(),
        invoice: TapToPayInvoiceData = TapToPayInvoiceData(),
        orderDescription: String? = null,
    ) = initiate(entryPoint, deviceId, details, idempotencyKey, customer, invoice, orderDescription)

    // Opening a transaction

    @Test
    fun `opening a transaction posts to the route and answers with the identifier`() =
        runTest(timeout = timeout) {
            val (transport, client) = client(answer(approved("""{"paymentTransId":"$TRANS_ID"}"""), 201))

            val paymentTransId = client.open(orderDescription = "Table 4")

            assertEquals(TRANS_ID, paymentTransId)
            assertEquals("POST", transport.request.method.wireName)
            assertEquals("/api/v2/MoneyIn/initiate", transport.request.path)
            assertEquals("/api/v2/MoneyIn/initiate", transport.request.route)
            assertEquals("application/json", transport.request.headers["Content-Type"])
            val body = transport.bodyText()
            assertTrue(body, body.contains(""""entryPoint":"$ENTRY""""))
            assertTrue(body, body.contains(""""device":"$DEVICE""""))
            assertTrue(body, body.contains(""""orderDescription":"Table 4""""))
        }

    @Test
    fun `an order description nobody gave is sent empty rather than omitted`() =
        runTest(timeout = timeout) {
            val (transport, client) = client(answer(approved("""{"paymentTransId":"$TRANS_ID"}""")))

            client.open()

            assertTrue(transport.bodyText(), transport.bodyText().contains(""""orderDescription":""""))
        }

    @Test
    fun `a declined opening is the payment being refused`() =
        runTest(timeout = timeout) {
            val (_, client) = client(answer(declined(code = "D05", reason = "Insufficient funds")))

            val failure = runCatching { client.open() }.exceptionOrNull()

            assertTrue("$failure", failure is TTPTransactionException.Refused)
            assertEquals("D05", (failure as TTPTransactionException.Refused).code)
        }

    @Test
    fun `an opening the service neither approved nor declined is the service's problem`() =
        runTest(timeout = timeout) {
            val (_, client) = client(answer("""{"code":"E7018","reason":"Device not found"}"""))

            val failure = runCatching { client.open() }.exceptionOrNull()

            assertTrue("$failure", failure is TTPTransactionException.ServiceRejected)
        }

    @Test
    fun `an approval carrying no identifier cannot be read, and is not recorded as a success first`() =
        runTest(timeout = timeout) {
            val (_, client) = client(answer("""{"code":"A01"}"""))

            val failure = runCatching { client.open() }.exceptionOrNull()

            assertTrue("$failure", failure is TTPTransactionException.Undecodable)
            // The order is the point. A success record written before the throw leaves an incident reading
            // as a call that worked and a caller that never got an answer.
            assertFalse(
                "${logger.records.map { it.message }}",
                logger.records.any { it.message == "the transaction was opened" },
            )
        }

    @Test
    fun `a body that is not the envelope cannot be read`() =
        runTest(timeout = timeout) {
            val (_, client) = client(answer("<html>gateway error</html>"))

            val failure = runCatching { client.open() }.exceptionOrNull()

            assertTrue("$failure", failure is TTPTransactionException.Undecodable)
        }

    @Test
    fun `the body it could not read is not carried out on the cause chain`() =
        runTest(timeout = timeout) {
            // A well-formed body of the wrong shape is refused without a decoder throwing, so its cause is
            // null and there is nothing to carry out. This one is malformed, which is what reaches the
            // decoder: kotlinx appends the input it choked on to its message, and a real body here holds a
            // paymentTransId and the processor's own fields.
            val body = """{"responseData":"tell-tale-payment-identifier","""
            val (_, client) = client(answer(body))

            val failure = runCatching { client.open() }.exceptionOrNull()

            assertTrue("$failure", failure is TTPTransactionException.Undecodable)
            // The whole chain, because a crash reporter renders all of it and the host's reporter is
            // outside anything this SDK scrubs.
            generateSequence(failure) { it.cause }.forEach { link ->
                assertFalse(
                    "the rejected body reached ${link.javaClass.name}: ${link.message}",
                    link.message.orEmpty().contains("tell-tale-payment-identifier"),
                )
            }
        }

    @Test
    fun `an approval whose identifier is blank cannot be read either`() =
        runTest(timeout = timeout) {
            // The field is required, so kotlinx accepts an empty string for it. A blank identifier reaches
            // the reader, takes a card, and then fails the closing call's own nonblank check.
            val (_, client) = client(answer(approved("""{"paymentTransId":""}""")))

            val failure = runCatching { client.open() }.exceptionOrNull()

            assertTrue("$failure", failure is TTPTransactionException.Undecodable)
        }

    @Test
    fun `opening names the attempt, so a repeat of it is recognizable as a repeat`() =
        runTest(timeout = timeout) {
            val (transport, client) = client(answer(approved("""{"paymentTransId":"$TRANS_ID"}""")))

            client.open(idempotencyKey = "the-attempt")

            assertEquals("the-attempt", transport.request.headers["idempotencyKey"])
            // The whole set, so a header added here later is a decision rather than a side effect.
            assertEquals(setOf("Content-Type", "idempotencyKey"), transport.request.headers.keys)
        }

    @Test
    fun `closing names no attempt, because it names the transaction instead`() =
        runTest(timeout = timeout) {
            val (transport, client) = client(answer(""))

            client.update(TRANS_ID, cardRead())

            assertEquals(setOf("Content-Type"), transport.request.headers.keys)
        }

    @Test
    fun `a key the transport could not send is refused before anything goes out`() =
        runTest(timeout = timeout) {
            // Padding and a control character both survive a `String` and fail at the connection, where the
            // message names neither the key nor the field.
            for (unusable in listOf("", " ", " padded", "padded ", "two\nlines")) {
                val (transport, client) = client(answer(approved("""{"paymentTransId":"$TRANS_ID"}""")))

                val failure = runCatching { client.open(idempotencyKey = unusable) }.exceptionOrNull()

                assertTrue("<$unusable> was accepted: $failure", failure is IllegalArgumentException)
                assertEquals("<$unusable> reached the wire", 0, transport.requests.size)
            }
        }

    @Test
    fun `opening is never retried, because a suppressed repeat answers with nothing to carry on with`() =
        runTest(timeout = timeout) {
            val (transport, client) = client(answer("", 500))

            val failure = runCatching { client.open() }.exceptionOrNull()

            assertTrue("$failure", failure is PayabliException)
            assertEquals("one attempt only", 1, transport.requests.size)
        }

    @Test
    fun `an entry point or a device nobody named is refused before anything is sent`() =
        runTest(timeout = timeout) {
            val (transport, client) = client()

            assertTrue(runCatching { client.open(entryPoint = " ") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { client.open(deviceId = "") }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, transport.requests.size)
        }

    // Closing a transaction

    @Test
    fun `closing a transaction patches the encoded path and carries the processor's response`() =
        runTest(timeout = timeout) {
            val (transport, client) = client(answer(approved("""{"paymentTransId":"$TRANS_ID"}""")))
            val response = """{"gatewayResponse":{"transactionState":"CAPTURED"}}"""

            client.update(TRANS_ID, cardRead(providerResponse = response))

            assertEquals("PATCH", transport.request.method.wireName)
            assertEquals("/api/v2/MoneyIn/update/$TRANS_ID", transport.request.path)
            assertEquals("/api/v2/MoneyIn/update/{transId}", transport.request.route)
            assertEquals("application/json", transport.request.headers["Content-Type"])
            assertEquals("""{"fiservResponse":$response}""", transport.bodyText())
        }

    @Test
    fun `a tap that never happened is reported under the reason the service files it as`() =
        runTest(timeout = timeout) {
            val (transport, client) = client(answer(approved("""{"paymentTransId":"$TRANS_ID"}""")))

            client.updateAfterFailedRead(TRANS_ID, "the card moved away")

            val body = transport.bodyText()
            assertTrue(body, body.contains(""""failureReason":"nfc_read""""))
            assertTrue(body, body.contains(""""description":"the card moved away""""))
            assertFalse(body, body.contains("fiservResponse"))
        }

    @Test
    fun `closing is retried, because the service writes nothing when the outcome has not moved`() =
        runTest(timeout = timeout) {
            val (transport, client) =
                client(answer("", 503), answer("", 500), answer(approved("""{"paymentTransId":"$TRANS_ID"}""")))

            client.update(TRANS_ID, cardRead())

            assertEquals(3, transport.requests.size)
        }

    @Test
    fun `closing gives up after the third attempt and reports the real failure`() =
        runTest(timeout = timeout) {
            val (transport, client) = client(answer("", 500), answer("", 500), answer("", 500))

            val failure = runCatching { client.update(TRANS_ID, cardRead()) }.exceptionOrNull()

            assertEquals(PayabliErrorCode.SERVER_ERROR, (failure as PayabliException).code)
            assertEquals(3, transport.requests.size)
        }

    @Test
    fun `a refused close is not retried, because sending it again cannot change it`() =
        runTest(timeout = timeout) {
            val (transport, client) = client(answer("", 400))

            runCatching { client.update(TRANS_ID, cardRead()) }

            assertEquals(1, transport.requests.size)
        }

    @Test
    fun `an identifier the service would read as another route is encoded into one segment`() =
        runTest(timeout = timeout) {
            val (transport, client) = client(answer(approved("""{"paymentTransId":"x"}""")))

            client.update("a/b?c", cardRead())

            assertEquals("/api/v2/MoneyIn/update/a%2Fb%3Fc", transport.request.path)
        }

    // The feature switch

    @Test
    fun `a paypoint that cannot take card-present payments says so on both routes`() =
        runTest(timeout = timeout) {
            val (openTransport, openClient) = client(answer("", 404))
            assertTrue(runCatching { openClient.open() }.exceptionOrNull() is TTPTransactionException.NotEnabled)
            assertEquals(1, openTransport.requests.size)

            val closeTransport = FakeTransactionTransport(answer("", 404))
            val closeClient = TTPTransactionClient(closeTransport, logger)
            val failure = runCatching { closeClient.update(TRANS_ID, cardRead()) }.exceptionOrNull()

            assertTrue("$failure", failure is TTPTransactionException.NotEnabled)
            assertEquals("a switch nobody flipped is not worth three attempts", 1, closeTransport.requests.size)
        }

    // The seam between the reader and the close

    @Test
    fun `what the reader answered is what the close sends`() =
        runTest(timeout = timeout) {
            val response = """{"gatewayResponse":{"transactionState":"AUTHORIZED"}}"""
            val provider = FakeTapToPayProvider(readResult = cardRead(providerResponse = response))
            val (transport, client) = client(answer(approved("""{"paymentTransId":"$TRANS_ID"}""")))

            val read =
                provider.startReading(
                    CardReadRequest(
                        amount = BigDecimal("10.00"),
                        merchantTransactionId = TRANS_ID,
                        merchantOrderId = TRANS_ID,
                        merchantInvoiceNumber = null,
                    ),
                )
            client.update(TRANS_ID, read)

            assertEquals(TRANS_ID, provider.lastReadRequest?.merchantTransactionId)
            assertEquals(
                "the reader is charged under the identifier Payabli minted",
                TRANS_ID,
                provider.lastReadRequest?.merchantOrderId,
            )
            assertEquals("""{"fiservResponse":$response}""", transport.bodyText())
        }

    // Logging

    @Test
    fun `a call that failed at the transport is recorded with something to tell the failures apart`() =
        runTest(timeout = timeout) {
            // The status is not enough on its own: a retried close and a paypoint that is not enabled both
            // reach this record, and an incident is read by what distinguishes them.
            val (_, server) = client(answer("", 500))
            runCatching { server.open() }

            val (_, switch) = client(answer("", 404))
            runCatching { switch.open() }

            val failures = logger.records.filter { it.message == "the transaction call failed at the transport" }
            assertEquals("both calls were recorded", 2, failures.size)
            failures.forEach { record ->
                assertTrue("a failed call named no error code: ${record.fieldNames}", "errorCode" in record.fieldNames)
            }
        }

    @Test
    fun `nothing sensitive reaches a log record`() =
        runTest(timeout = timeout) {
            // Every path that writes a record, because each writes a different set of fields and a test
            // that reaches only the failing ones leaves the successful ones unread.
            val cardData = """{"source":{"card":{"last4":"1111"}}}"""
            val (_, succeeding) =
                client(
                    answer(approved("""{"paymentTransId":"$TRANS_ID"}""")),
                    answer(approved("""{"paymentTransId":"$TRANS_ID"}""")),
                )
            succeeding.open(customer = TapToPayCustomerData(firstName = "Ada", billingEmail = "ada@example.com"))
            succeeding.update(TRANS_ID, cardRead(providerResponse = cardData))

            val (_, failing) = client(answer(declined()), answer("", 500), answer("", 500), answer("", 500))
            runCatching { failing.open() }
            runCatching { failing.update(TRANS_ID, cardRead(providerResponse = cardData)) }

            val names = logger.records.flatMap { it.fieldNames }.toSet()
            assertTrue("$names", ALLOWED_LOG_FIELDS.containsAll(names))
            assertTrue(
                "the close reports its body by size, so the size has to be there: $names",
                "contentLength" in names,
            )
            logger.records.forEach { record ->
                listOf("Ada", "ada@example.com", "1111", TRANS_ID).forEach { secret ->
                    assertFalse("${record.message} leaked $secret", record.message.contains(secret))
                }
                assertNull("a log record must not carry a cause that can print a body", record.throwable)
            }
        }
}
