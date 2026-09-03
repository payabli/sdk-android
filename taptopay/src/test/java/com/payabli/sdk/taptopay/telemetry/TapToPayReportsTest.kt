package com.payabli.sdk.taptopay.telemetry

import com.payabli.sdk.core.telemetry.TelemetryCatalog
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.adapters.CardReaderFailure
import com.payabli.sdk.taptopay.adapters.ReaderFailureKind
import com.payabli.sdk.taptopay.network.TTPTransactionException
import com.payabli.sdk.taptopay.session.TapToPayFailureReason
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the card-present lifecycle reports, against the catalog that decides what it may.
 *
 * A property the catalog does not declare is scrubbed on the way in, so a call site that sends one loses it
 * with nothing said. These assert the keys rather than only the values, which is what catches that.
 */
class TapToPayReportsTest {
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
    fun `a started event carries nothing at all`() {
        TapToPayReports.chargeStarted()

        val (event, properties) = recorded.single()
        assertEquals(TelemetryEvents.TTP_CHARGE_STARTED, event)
        assertEquals(emptyMap<String, String>(), properties)
    }

    @Test
    fun `a succeeded event carries how long it took, and nothing else`() {
        TapToPayReports.chargeSucceeded(System.nanoTime())

        val (event, properties) = recorded.single()
        assertEquals(TelemetryEvents.TTP_CHARGE_SUCCEEDED, event)
        assertEquals(setOf(TelemetryProperty.DURATION_MS.key), properties.keys)
    }

    @Test
    fun `a reader refusal reports the vendor code, and never the vendor's words`() {
        TapToPayReports.chargeFailed(deniedBy("677", "Device has been suspended or deactivated"), System.nanoTime())

        val (_, properties) = recorded.single()
        assertEquals("677", properties[TelemetryProperty.CODE.key])
        assertTrue(
            "vendor prose reached telemetry: $properties",
            properties.values.none { it.contains("suspended", ignoreCase = true) },
        )
    }

    @Test
    fun `a denied device failed, because no payment was declined`() {
        // `declined` is the money path's word for a payment the service refused, and the property is shared
        // with it. A handset the vendor will not arm is a failure; the vendor code says which.
        TapToPayReports.initializeFailed(deniedBy("677"), System.nanoTime())

        val (event, properties) = recorded.single()
        assertEquals(TelemetryEvents.TTP_INITIALIZE_FAILED, event)
        assertEquals(TelemetryProperties.Outcome.FAILED, properties[TelemetryProperty.OUTCOME.key])
        assertEquals("677", properties[TelemetryProperty.CODE.key])
    }

    @Test
    fun `a payment the service refused is the one thing that is declined`() {
        TapToPayReports.chargeFailed(
            TTPTransactionException.Refused(code = "D0001", reason = null),
            System.nanoTime(),
        )

        val (event, properties) = recorded.single()
        assertEquals(TelemetryEvents.TTP_CHARGE_FAILED, event)
        assertEquals(TelemetryProperties.Outcome.DECLINED, properties[TelemetryProperty.OUTCOME.key])
    }

    @Test
    fun `a reader that did not come up says which kind and which code`() {
        TapToPayReports.nfcFailed(refusal(ReaderFailureKind.DEVICE_DENIED, "677"), System.nanoTime())
        TapToPayReports.nfcFailed(refusal(ReaderFailureKind.DEVICE_DENIED_UNCONFIRMED, "705"), System.nanoTime())

        assertEquals(
            listOf("device_denied" to "677", "device_denied_unconfirmed" to "705"),
            recorded.map { it.second[TelemetryProperty.REASON.key] to it.second[TelemetryProperty.CODE.key] },
        )
    }

    @Test
    fun `a refusal the mapping does not recognise still says which code it was`() {
        // The case the reason alone cannot answer, and the reason this event carries a code at all.
        TapToPayReports.nfcFailed(refusal(ReaderFailureKind.UNCLASSIFIED, "E-1"), System.nanoTime())

        val (event, properties) = recorded.single()
        assertEquals(TelemetryEvents.TTP_NFC_FAILED, event)
        assertEquals("unclassified", properties[TelemetryProperty.REASON.key])
        assertEquals("E-1", properties[TelemetryProperty.CODE.key])
    }

    @Test
    fun `a reader that timed out locally has a kind and no code to send`() {
        TapToPayReports.nfcFailed(CardReaderFailure(ReaderFailureKind.TIMED_OUT), System.nanoTime())

        val (_, properties) = recorded.single()
        assertEquals("timed_out", properties[TelemetryProperty.REASON.key])
        assertNull("there is no vendor code for a local deadline", properties[TelemetryProperty.CODE.key])
    }

    @Test
    fun `a state change names both ends, and says why only when the move failed`() {
        TapToPayReports.sessionStateChanged(TapToPaySessionState.Idle, TapToPaySessionState.AttestingDevice)
        TapToPayReports.sessionStateChanged(
            TapToPaySessionState.AttestingDevice,
            TapToPaySessionState.Failed(TapToPayFailureReason.DEVICE_INELIGIBLE),
        )

        val (_, moved) = recorded.first()
        assertEquals("idle", moved[TelemetryProperty.FROM.key])
        assertEquals("attesting_device", moved[TelemetryProperty.TO.key])
        assertNull("a move that did not fail has no reason", moved[TelemetryProperty.REASON.key])

        val (_, failed) = recorded.last()
        assertEquals("device_ineligible", failed[TelemetryProperty.REASON.key])
    }

    @Test
    fun `every property every site sends is one the catalog allows`() {
        val startedAt = System.nanoTime()
        val failure = deniedBy("677")

        TapToPayReports.initializeStarted()
        TapToPayReports.initializeSucceeded(startedAt)
        TapToPayReports.initializeFailed(failure, startedAt)
        TapToPayReports.attestationStarted()
        TapToPayReports.attestationSucceeded(startedAt)
        TapToPayReports.attestationFailed(failure, startedAt)
        TapToPayReports.reinitializeStarted()
        TapToPayReports.reinitializeSucceeded(startedAt)
        TapToPayReports.chargeStarted()
        TapToPayReports.chargeSucceeded(startedAt)
        TapToPayReports.chargeFailed(failure, startedAt)
        TapToPayReports.nfcStarted()
        TapToPayReports.nfcSucceeded(startedAt)
        // Carries a code, so a catalog that stopped allowing one is caught here rather than in the wire.
        TapToPayReports.nfcFailed(refusal(ReaderFailureKind.DEVICE_DENIED, "677"), startedAt)
        TapToPayReports.sessionStateChanged(
            TapToPaySessionState.Idle,
            TapToPaySessionState.Failed(TapToPayFailureReason.SERVICE_UNAVAILABLE),
        )

        // Scrubbing is what the recorder does on the way in, so anything it removes is a property this
        // site sends and the service will never see.
        recorded.forEach { (event, properties) ->
            assertEquals(
                "$event sent a property the catalog drops",
                properties,
                TelemetryCatalog.scrub(event, properties),
            )
        }
    }

    @Test
    fun `a failure forces the batch to be sent`() {
        TapToPayReports.chargeFailed(deniedBy("677"), System.nanoTime())

        val (event, properties) = recorded.single()
        assertTrue("a failed charge has to flush", TelemetryCatalog.forcesSend(event, properties))
    }

    private fun refusal(
        kind: ReaderFailureKind,
        code: String,
    ) = CardReaderFailure(kind, code = code)

    private fun deniedBy(
        code: String,
        message: String? = null,
    ): Throwable =
        CardReaderException.DeviceDenied(
            CardReaderFailure(ReaderFailureKind.DEVICE_DENIED, code = code, detail = message),
        )
}
