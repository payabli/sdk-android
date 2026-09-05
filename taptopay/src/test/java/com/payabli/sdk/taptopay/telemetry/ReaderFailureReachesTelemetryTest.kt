package com.payabli.sdk.taptopay.telemetry

import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.taptopay.adapters.CardReaderFailure
import com.payabli.sdk.taptopay.adapters.FakeCardReaderGateway
import com.payabli.sdk.taptopay.adapters.FiservAndroidCardReader
import com.payabli.sdk.taptopay.adapters.ReaderFailureKind
import com.payabli.sdk.taptopay.adapters.eligibility
import com.payabli.sdk.taptopay.adapters.readerCredentials
import com.payabli.sdk.taptopay.provider.CardReadRequest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

/**
 * That a vendor refusal reaches telemetry by its code, driven through the reader rather than posed.
 *
 * The code reaches telemetry twice: on `ttp.nfc.failed`, which the catalog now allows it on, and on the
 * phase that encloses the failure, which reads it off the cause. Asserting either on a hand-built exception
 * proves the mapping and not the path, which is the half that can rot: a call site that stops passing the
 * cause, or a `codeOf` that stops walking to it, leaves every assertion about the mapping green.
 */
class ReaderFailureReachesTelemetryTest {
    private val recorded = mutableListOf<Pair<String, Map<String, String>>>()

    @Before
    fun install() {
        TelemetryRecorders.install { event, properties -> recorded += event to properties }
    }

    @After
    fun clear() {
        TelemetryRecorders.clear()
    }

    @Test
    fun `a tap the vendor refused reports the kind, and the code on the phase around it`() =
        runTest(timeout = TEST_TIMEOUT) {
            val denial = CardReaderFailure(ReaderFailureKind.DEVICE_DENIED, code = "677", detail = VENDOR_PROSE)
            val reader = FiservAndroidCardReader(FakeCardReaderGateway(readFailure = denial), eligibility())
            reader.configure(readerCredentials())
            reader.prepareReader()

            val failure = runCatching { reader.startReading(request()) }.exceptionOrNull()

            // The reader's own event now answers both halves without a join.
            val (nfcEvent, nfcProperties) = recorded.last()
            assertEquals(TelemetryEvents.TTP_NFC_FAILED, nfcEvent)
            assertEquals("device_denied", nfcProperties[TelemetryProperty.REASON.key])
            assertEquals("677", nfcProperties[TelemetryProperty.CODE.key])

            // And the code is still reachable from what the reader threw, which is how the enclosing
            // phase reports it. Without this the number would stop at the device.
            recorded.clear()
            TapToPayReports.chargeFailed(requireNotNull(failure), System.nanoTime())
            val (_, chargeProperties) = recorded.single()
            assertEquals("677", chargeProperties[TelemetryProperty.CODE.key])
        }

    @Test
    fun `the code an unexplained refusal carries reaches telemetry too, and stays apart from 677`() =
        runTest(timeout = TEST_TIMEOUT) {
            val denial =
                CardReaderFailure(ReaderFailureKind.DEVICE_DENIED_UNCONFIRMED, code = "705", detail = VENDOR_PROSE)
            val reader = FiservAndroidCardReader(FakeCardReaderGateway(readFailure = denial), eligibility())
            reader.configure(readerCredentials())
            reader.prepareReader()

            val failure = runCatching { reader.startReading(request()) }.exceptionOrNull()

            val (_, nfcProperties) = recorded.last()
            assertEquals("device_denied_unconfirmed", nfcProperties[TelemetryProperty.REASON.key])
            assertEquals("705", nfcProperties[TelemetryProperty.CODE.key])

            recorded.clear()
            TapToPayReports.chargeFailed(requireNotNull(failure), System.nanoTime())
            assertEquals("705", recorded.single().second[TelemetryProperty.CODE.key])
        }

    @Test
    fun `an arming the vendor refused carries its code out, because no nfc event covers arming`() =
        runTest(timeout = TEST_TIMEOUT) {
            val denial = CardReaderFailure(ReaderFailureKind.DEVICE_DENIED, code = "677", detail = VENDOR_PROSE)
            val reader = FiservAndroidCardReader(FakeCardReaderGateway(prepareFailure = denial), eligibility())
            reader.configure(readerCredentials())

            val failure = runCatching { reader.prepareReader() }.exceptionOrNull()

            assertTrue("arming reports no nfc event", recorded.none { it.first.startsWith("ttp.nfc") })

            TapToPayReports.initializeFailed(requireNotNull(failure), System.nanoTime())
            val (event, properties) = recorded.single()
            assertEquals(TelemetryEvents.TTP_INITIALIZE_FAILED, event)
            assertEquals("677", properties[TelemetryProperty.CODE.key])
        }

    @Test
    fun `nothing the vendor wrote reaches any property, on either path`() =
        runTest(timeout = TEST_TIMEOUT) {
            val denial =
                CardReaderFailure(
                    ReaderFailureKind.DEVICE_DENIED,
                    code = "677",
                    detail = VENDOR_PROSE,
                    additionalInfo = VENDOR_PROSE,
                )
            val reader = FiservAndroidCardReader(FakeCardReaderGateway(readFailure = denial), eligibility())
            reader.configure(readerCredentials())
            reader.prepareReader()
            val failure = runCatching { reader.startReading(request()) }.exceptionOrNull()
            TapToPayReports.chargeFailed(requireNotNull(failure), System.nanoTime())

            assertNotNull("nothing was reported, so this proves nothing", recorded.firstOrNull())
            val leaked =
                recorded.flatMap { (event, properties) ->
                    properties.entries
                        .filter { it.value.contains("suspended", ignoreCase = true) }
                        .map { "$event.${it.key}=${it.value}" }
                }
            assertEquals("the vendor's words reached telemetry", emptyList<String>(), leaked)
        }

    private fun request() =
        CardReadRequest(
            amount = BigDecimal("1.00"),
            merchantTransactionId = "a-transaction",
            merchantOrderId = "an-order",
            merchantInvoiceNumber = null,
        )

    private companion object {
        val TEST_TIMEOUT = 5.seconds

        /** The exact free text the vendor answers 677 with, so the assertion is about the real string. */
        const val VENDOR_PROSE = "Device has been suspended or deactivated"
    }
}
