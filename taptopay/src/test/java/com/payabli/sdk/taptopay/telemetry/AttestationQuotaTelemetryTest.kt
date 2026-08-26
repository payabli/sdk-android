package com.payabli.sdk.taptopay.telemetry

import com.google.android.play.core.integrity.model.IntegrityErrorCode
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import com.payabli.sdk.core.telemetry.TelemetryCatalog
import com.payabli.sdk.core.telemetry.TelemetryEvents
import com.payabli.sdk.core.telemetry.TelemetryProperties
import com.payabli.sdk.core.telemetry.TelemetryRecorders
import com.payabli.sdk.taptopay.attestation.VerdictClass
import com.payabli.sdk.taptopay.attestation.impl.PlayIntegrityErrorMapping
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The one attestation refusal whose cause is not the device that hit it.
 *
 * The request budget belongs to the cloud project and is shared across every app embedding the SDK, so one
 * integrator's traffic exhausts it for all of them while each device sees only its own failure. Counted
 * centrally or not at all.
 *
 * **The classification tests do not cover this and cannot.** They assert what `failureFor` returns, and the
 * report is a side effect with no recorder installed, so deleting the whole `record` call left them green.
 * These install one.
 */
class AttestationQuotaTelemetryTest {
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
    fun `the standard request shape reports the quota it exhausted`() {
        PlayIntegrityErrorMapping.failureFor(StandardIntegrityErrorCode.TOO_MANY_REQUESTS, VerdictClass.STANDARD)

        val (event, properties) = recorded.single()
        assertEquals(TelemetryEvents.TTP_ATTESTATION_QUOTA_EXHAUSTED, event)
        assertEquals("standard", properties[TelemetryProperties.REASON])
        assertEquals(
            StandardIntegrityErrorCode.TOO_MANY_REQUESTS.toString(),
            properties[TelemetryProperties.CODE],
        )
    }

    /**
     * The two shapes have separate code tables, so a count that did not say which cannot be read.
     *
     * Asserted as a difference rather than a value: the point is that the two are distinguishable at the far
     * end, and a mapping that reported one shape for both would satisfy every other assertion here.
     */
    @Test
    fun `the classic request shape is told apart from the standard one`() {
        PlayIntegrityErrorMapping.failureFor(IntegrityErrorCode.TOO_MANY_REQUESTS, VerdictClass.CLASSIC)

        val classic = recorded.single().second[TelemetryProperties.REASON]
        recorded.clear()

        PlayIntegrityErrorMapping.failureFor(StandardIntegrityErrorCode.TOO_MANY_REQUESTS, VerdictClass.STANDARD)

        assertEquals("classic", classic)
        assertTrue(classic != recorded.single().second[TelemetryProperties.REASON])
    }

    /**
     * Only the budget, and never a refusal the device caused.
     *
     * An event emitted for a neighbouring code would be a quota alarm that fires on an ordinary network
     * failure, which is the shape that teaches people to stop reading an alarm.
     */
    @Test
    fun `a refusal that is not the quota reports nothing`() {
        listOf(
            StandardIntegrityErrorCode.NETWORK_ERROR,
            StandardIntegrityErrorCode.INTERNAL_ERROR,
            StandardIntegrityErrorCode.CLIENT_TRANSIENT_ERROR,
        ).forEach { PlayIntegrityErrorMapping.failureFor(it, VerdictClass.STANDARD) }

        listOf(
            IntegrityErrorCode.NETWORK_ERROR,
            IntegrityErrorCode.INTERNAL_ERROR,
        ).forEach { PlayIntegrityErrorMapping.failureFor(it, VerdictClass.CLASSIC) }

        assertTrue(recorded.toString(), recorded.isEmpty())
    }

    /** Every key it sends survives the gate, so the report is not scrubbed to nothing on the way out. */
    @Test
    fun `every property it reports is one the catalog allows`() {
        PlayIntegrityErrorMapping.failureFor(StandardIntegrityErrorCode.TOO_MANY_REQUESTS, VerdictClass.STANDARD)

        val (event, properties) = recorded.single()
        assertEquals(properties, TelemetryCatalog.scrub(event, properties))
    }
}
