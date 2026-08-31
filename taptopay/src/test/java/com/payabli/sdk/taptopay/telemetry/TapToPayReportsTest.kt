package com.payabli.sdk.taptopay.telemetry

import com.payabli.sdk.core.telemetry.TelemetryCatalog
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryProperty
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.adapters.CardReaderFailure
import com.payabli.sdk.taptopay.adapters.ReaderFailureKind
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
    fun `a denied device is declined rather than failed, because the vendor answered`() {
        TapToPayReports.initializeFailed(deniedBy("677"), System.nanoTime())

        val (event, properties) = recorded.single()
        assertEquals(TelemetryEvents.TTP_INITIALIZE_FAILED, event)
        assertEquals(TelemetryProperties.Outcome.DECLINED, properties[TelemetryProperty.OUTCOME.key])
    }

    @Test
    fun `a reader that did not come up says which kind, so 677 and 705 stay apart`() {
        TapToPayReports.nfcFailed(ReaderFailureKind.DEVICE_DENIED, System.nanoTime())
        TapToPayReports.nfcFailed(ReaderFailureKind.DEVICE_DENIED_UNCONFIRMED, System.nanoTime())

        val reasons = recorded.map { it.second[TelemetryProperty.REASON.key] }
        assertEquals(listOf("device_denied", "device_denied_unconfirmed"), reasons)
    }

    @Test
    fun `the failed reader event declares no code, so none is sent`() {
        TapToPayReports.nfcFailed(ReaderFailureKind.DEVICE_DENIED, System.nanoTime())

        val (event, properties) = recorded.single()
        assertEquals(TelemetryEvents.TTP_NFC_FAILED, event)
        assertNull("the catalog declares no code for this event", properties[TelemetryProperty.CODE.key])
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
        TapToPayReports.nfcFailed(ReaderFailureKind.TIMED_OUT, startedAt)
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

    private fun deniedBy(
        code: String,
        message: String? = null,
    ): Throwable =
        CardReaderException.DeviceDenied(
            CardReaderFailure(ReaderFailureKind.DEVICE_DENIED, code = code, detail = message),
        )
}
