package com.payabli.sdk.payin.client

import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliException
import com.payabli.sdk.core.network.HttpMethod
import com.payabli.sdk.core.network.PayabliResponse
import com.payabli.sdk.payin.model.PayInException
import com.payabli.sdk.payin.model.PayInTransaction
import com.payabli.sdk.payin.model.PayInTransactionStatus
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

/** Reading a transaction back: the request, the record it decodes, and the refusals it names. */
class MoneyInReadTest {
    private val timeout = 5.seconds

    @Test
    fun `a read resolves the path, logs the template and sends no body and no key`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(RECORD)

            MoneyInClient(transport, RecordingSdkLogger()).readTransaction("101-abc")

            assertEquals(HttpMethod.GET, transport.request?.method)
            assertEquals("/api/MoneyIn/details/101-abc", transport.request?.path)
            assertEquals("/api/MoneyIn/details/{transId}", transport.request?.route)
            assertNull(transport.request?.body)
            assertFalse(
                transport.request
                    ?.headers
                    .orEmpty()
                    .containsKey("idempotencyKey"),
            )
        }

    @Test
    fun `a blank identifier is refused before anything is sent`() =
        runTest(timeout = timeout) {
            listOf("  ", ".", "..").forEach { transId ->
                val transport = FakePayInTransport.answering(RECORD)

                val failure = read(transport, transId) as? PayInException.InvalidInput

                assertEquals(transId, "transId", failure?.field)
                assertNull(transId, transport.request)
            }
        }

    @Test
    fun `the record fills the fields a read carries, and reads its status`() =
        runTest(timeout = timeout) {
            val transaction =
                MoneyInClient(FakePayInTransport.answering(RECORD), RecordingSdkLogger())
                    .readTransaction("101-abc")

            assertEquals("101-abc", transaction.paymentTransId)
            assertEquals("gw-9", transaction.gatewayTransId)
            assertEquals("order-1", transaction.orderId)
            assertEquals("card", transaction.method)
            assertEquals(5, transaction.transStatus)
            assertEquals(PayInTransactionStatus.Voided, transaction.status)
            assertEquals(42L, transaction.paypointId)
            assertEquals(BigDecimal("1.10"), transaction.totalAmount)
            assertEquals(BigDecimal("1.00"), transaction.netAmount)
            assertEquals(BigDecimal("0.10"), transaction.feeAmount)
            assertEquals(BigDecimal("0.00"), transaction.surchargeFee)
        }

    @Test
    fun `fields only one platform carries stay null on a read`() =
        runTest(timeout = timeout) {
            val transaction =
                MoneyInClient(FakePayInTransport.answering(RECORD), RecordingSdkLogger())
                    .readTransaction("101-abc")

            assertNull(transaction.connectorName)
            assertNull(transaction.customerId)
        }

    @Test
    fun `nothing visible under the identifier is a transaction not found`() =
        runTest(timeout = timeout) {
            val failure = read(FakePayInTransport.answering(LEGACY_7001, statusCode = 400)) as? PayabliException

            assertEquals(PayabliErrorType.TRANSACTION_NOT_FOUND, failure?.type)
        }

    @Test
    fun `a caller refused this transaction is a permission refusal, not an expired token`() =
        runTest(timeout = timeout) {
            val failure = read(FakePayInTransport.answering(LEGACY_9001, statusCode = 401)) as? PayabliException

            assertEquals(PayabliErrorType.PERMISSION_DENIED, failure?.type)
        }

    @Test
    fun `the read tells the transport which rejection refuses its caller`() =
        runTest(timeout = timeout) {
            val transport = FakePayInTransport.answering(RECORD)
            MoneyInClient(transport, RecordingSdkLogger()).readTransaction("101-abc")
            val isCallerRefusal = transport.request!!.isCallerRefusal

            assertTrue(isCallerRefusal(response(401, LEGACY_9001)))
            assertFalse("a 401 with no body is the credential's", isCallerRefusal(response(401, "")))
            assertFalse("9001 on another status is not this refusal", isCallerRefusal(response(400, LEGACY_9001)))
        }

    @Test
    fun `a 401 with no refusal code stays an expired token`() =
        runTest(timeout = timeout) {
            val failure = read(FakePayInTransport.answering("", statusCode = 401)) as? PayabliException

            assertEquals(PayabliErrorType.TOKEN_EXPIRED, failure?.type)
        }

    @Test
    fun `a missing grant is a permission refusal`() =
        runTest(timeout = timeout) {
            val failure = read(FakePayInTransport.answering("", statusCode = 403)) as? PayabliException

            assertEquals(PayabliErrorType.PERMISSION_DENIED, failure?.type)
        }

    @Test
    fun `another 400 is read by the status table`() =
        runTest(timeout = timeout) {
            val failure =
                read(FakePayInTransport.answering("""{"isSuccess":false,"responseCode":7002}""", statusCode = 400))
                    as? PayabliException

            assertEquals(PayabliErrorType.VALIDATION_ERROR, failure?.type)
        }

    @Test
    fun `a server failure is a server error`() =
        runTest(timeout = timeout) {
            val failure = read(FakePayInTransport.answering("", statusCode = 500)) as? PayabliException

            assertEquals(PayabliErrorType.SERVER_ERROR, failure?.type)
        }

    @Test
    fun `an answer that is not a record is unreadable`() =
        runTest(timeout = timeout) {
            assertTrue(read(FakePayInTransport.answering("not json")) is PayInException.Undecodable)
        }

    @Test
    fun `an answer that names no transaction is unreadable rather than empty`() =
        runTest(timeout = timeout) {
            assertTrue(read(FakePayInTransport.answering("{}")) is PayInException.Undecodable)
            assertTrue(read(FakePayInTransport.answering("""{"PaymentTransId":" "}""")) is PayInException.Undecodable)
        }

    private suspend fun read(
        transport: FakePayInTransport,
        transId: String = "101-abc",
    ): Throwable? =
        runCatching<PayInTransaction> { MoneyInClient(transport, RecordingSdkLogger()).readTransaction(transId) }
            .exceptionOrNull()

    private fun response(
        status: Int,
        body: String,
    ) = PayabliResponse(status, emptyMap(), body.toByteArray(Charsets.UTF_8))

    private companion object {
        /** A record as the route answers it: PascalCase, bare, and carrying fields a read never decodes. */
        const val RECORD =
            """{"PaymentTransId":"101-abc","GatewayTransId":"gw-9","OrderId":"order-1",""" +
                """"Method":"card","TransStatus":5,"SettlementStatus":0,"Operation":"Sale","PaypointId":42,""" +
                """"TotalAmount":1.10,"NetAmount":1.00,"FeeAmount":0.10,"SurchargeFee":0.00,""" +
                """"ConnectorName":"connector","PayorId":7,"IPAddress":"203.0.113.9",""" +
                """"PaymentData":{"MaskedAccount":"4xXXXXXXXXXX1111","AccountExp":"12/30",""" +
                """"binData":{"binCardBrand":"Visa"}},"Customer":{"FirstName":"Test"},""" +
                """"EmvAuthData":{"RequestEmvData":"9F02060000000001105A08XXXXXXXXXXXXXXXX"},""" +
                """"ResponseData":{"resultCode":"A0000","authcode":"123456"}}"""

        const val LEGACY_7001 =
            """{"isSuccess":false,"responseText":"Error","responseCode":7001,""" +
                """"responseData":{"explanation":"It may take a short while to appear"}}"""

        const val LEGACY_9001 = """{"isSuccess":false,"responseText":"Error","responseCode":9001}"""
    }
}
