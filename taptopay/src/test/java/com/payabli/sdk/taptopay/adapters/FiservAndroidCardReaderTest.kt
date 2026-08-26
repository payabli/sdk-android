package com.payabli.sdk.taptopay.adapters

import com.payabli.sdk.taptopay.provider.CardReadRequest
import com.payabli.sdk.taptopay.provider.DeviceIneligibleException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

private val TEST_TIMEOUT = 5.seconds

private fun readRequest(
    amount: BigDecimal = BigDecimal("12.34"),
    merchantTransactionId: String = "trans-1",
    merchantOrderId: String = "trans-1",
    merchantInvoiceNumber: String? = "invoice-1",
) = CardReadRequest(amount, merchantTransactionId, merchantOrderId, merchantInvoiceNumber)

/** The four phases, their order, and what a failure in each of them means. */
class FiservAndroidCardReaderTest {
    private fun readerFor(
        gateway: FakeCardReaderGateway = FakeCardReaderGateway(),
        eligibility: ReaderEligibility = eligibility(),
    ) = FiservAndroidCardReader(gateway, eligibility)

    @Test
    fun `eligibility is whatever the handset answered`() =
        runTest(timeout = TEST_TIMEOUT) {
            readerFor().checkEligibility()

            val refused = DeviceIneligibleException("this device has no contactless radio")
            val raised =
                runCatching { readerFor(eligibility = eligibility(refused)).checkEligibility() }.exceptionOrNull()

            assertSame(refused, raised)
        }

    @Test
    fun `bringing the reader up before it is configured is a defect in this SDK`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeCardReaderGateway()

            val failure = runCatching { readerFor(gateway).prepareReader() }.exceptionOrNull()

            assertTrue(failure.toString(), failure is IllegalStateException)
            assertEquals(0, gateway.prepareCount)
        }

    @Test
    fun `the reader comes up with what the credentials mapped to`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeCardReaderGateway()
            val reader = readerFor(gateway)

            reader.configure(readerCredentials(merchantId = "merchant-1", environment = "production"))
            reader.prepareReader()

            assertEquals(1, gateway.prepareCount)
            assertEquals("merchant-1", gateway.lastArming?.merchantId)
            assertEquals(ReaderEnvironment.PROD, gateway.lastArming?.environment)
        }

    @Test
    fun `one set of credentials brings the reader up once`() =
        runTest(timeout = TEST_TIMEOUT) {
            // They hold live vendor secrets and are not kept past the reader that takes them, so a second
            // arming has nothing to arm with and a session that needs one fetches it again.
            val gateway = FakeCardReaderGateway()
            val reader = readerFor(gateway)
            reader.configure(readerCredentials())
            reader.prepareReader()

            val failure = runCatching { reader.prepareReader() }.exceptionOrNull()

            assertTrue(failure.toString(), failure is IllegalStateException)
            assertEquals(1, gateway.prepareCount)
        }

    @Test
    fun `credentials that were refused leave nothing to arm with`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeCardReaderGateway()
            val reader = readerFor(gateway)

            val refusal =
                runCatching { reader.configure(readerCredentials(terminalId = "")) }.exceptionOrNull()
            val armed = runCatching { reader.prepareReader() }.exceptionOrNull()

            assertTrue(refusal.toString(), refusal is CardReaderException.CredentialsUnusable)
            assertTrue(armed.toString(), armed is IllegalStateException)
            assertEquals(0, gateway.prepareCount)
        }

    @Test
    fun `a reader that did not come up says so, and keeps what the vendor reported`() =
        runTest(timeout = TEST_TIMEOUT) {
            val refusal = CardReaderFailure(ReaderFailureKind.UNCLASSIFIED, code = "E-1")
            val reader = readerFor(FakeCardReaderGateway(prepareFailure = refusal))
            reader.configure(readerCredentials())

            val failure = runCatching { reader.prepareReader() }.exceptionOrNull()

            assertTrue(failure.toString(), failure is CardReaderException.ArmingFailed)
            assertSame(refusal, failure?.cause)
            // The code is the only part that tells one refusal from another, and a host has nothing else
            // to report.
            assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("E-1"))
        }

    @Test
    fun `everything the vendor reported survives to the caller`() =
        runTest(timeout = TEST_TIMEOUT) {
            // Read off the cause by whoever reports the failure.
            val refusal =
                CardReaderFailure(
                    kind = ReaderFailureKind.UNCLASSIFIED,
                    code = "677",
                    type = "FSSDK",
                    field = "",
                    detail = "Device Denied",
                    additionalInfo = "Device has been suspended or deactivated",
                )
            val reader = readerFor(FakeCardReaderGateway(prepareFailure = refusal))
            reader.configure(readerCredentials())

            val reported = runCatching { reader.prepareReader() }.exceptionOrNull()?.cause as? CardReaderFailure

            assertEquals("677", reported?.code)
            assertEquals("FSSDK", reported?.type)
            assertEquals("", reported?.field)
            assertEquals("Device Denied", reported?.detail)
            assertEquals("Device has been suspended or deactivated", reported?.additionalInfo)
        }

    @Test
    fun `a reader that never answers is reported instead of waited on`() =
        runTest(timeout = TEST_TIMEOUT) {
            // What the reader did on a handset: it took the credentials and produced nothing. Without a
            // bound the caller waits for as long as its scope lives, and a merchant sees a screen that says
            // it is working and never stops saying it.
            val gateway = FakeCardReaderGateway(prepareNeverAnswers = true)
            val reader = readerFor(gateway)
            reader.configure(readerCredentials())

            val failure = runCatching { reader.prepareReader() }.exceptionOrNull()

            assertTrue(failure.toString(), failure is CardReaderException.ArmingFailed)
            assertEquals(
                ReaderFailureKind.TIMED_OUT,
                (failure?.cause as? CardReaderFailure)?.kind,
            )
        }

    @Test
    fun `the identifier the payment was opened under is the one the reader is given`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway = FakeCardReaderGateway()

            readerFor(gateway).startReading(
                readRequest(merchantTransactionId = "trans-9", merchantOrderId = "trans-9"),
            )

            assertEquals("trans-9", gateway.lastCharge?.merchantTransactionId)
            assertEquals("trans-9", gateway.lastCharge?.merchantOrderId)
            assertEquals(BigDecimal("12.34"), gateway.lastCharge?.amount)
        }

    @Test
    fun `the result carries the network and the record the transaction client forwards`() =
        runTest(timeout = TEST_TIMEOUT) {
            val gateway =
                FakeCardReaderGateway(
                    record = chargeRecord(cardNetwork = "MASTERCARD", transactionState = "CAPTURED"),
                )

            val result = readerFor(gateway).startReading(readRequest())

            assertEquals("MASTERCARD", result.cardNetwork)
            assertTrue(result.providerResponse, result.providerResponse.contains("CAPTURED"))
        }

    @Test
    fun `a dead session is not a failed tap`() =
        runTest(timeout = TEST_TIMEOUT) {
            // A host repairs the two differently: one rebuilds the reader, the other takes the payment again.
            val dead = FakeCardReaderGateway(readFailure = CardReaderFailure(ReaderFailureKind.SESSION_UNUSABLE))

            val failure = runCatching { readerFor(dead).startReading(readRequest()) }.exceptionOrNull()

            assertTrue(failure.toString(), failure is CardReaderException.SessionUnusable)
        }

    @Test
    fun `every other reader failure is the tap that did not complete`() =
        runTest(timeout = TEST_TIMEOUT) {
            for (kind in listOf(ReaderFailureKind.CONTACTLESS_UNAVAILABLE, ReaderFailureKind.UNCLASSIFIED)) {
                val gateway = FakeCardReaderGateway(readFailure = CardReaderFailure(kind))

                val failure = runCatching { readerFor(gateway).startReading(readRequest()) }.exceptionOrNull()

                assertTrue("$kind became $failure", failure is CardReaderException.ReadFailed)
            }
        }

    @Test
    fun `nothing about a payment is printed`() {
        val printed = ReaderCharge(BigDecimal("12.34"), "trans-1", "trans-1").toString()

        assertTrue(printed, !printed.contains("12.34"))
    }
}
