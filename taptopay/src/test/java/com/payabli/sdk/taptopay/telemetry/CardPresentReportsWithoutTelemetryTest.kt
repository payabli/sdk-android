package com.payabli.sdk.taptopay.telemetry

import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import com.payabli.sdk.core.telemetry.TelemetryBootstrap
import com.payabli.sdk.taptopay.adapters.CardReaderException
import com.payabli.sdk.taptopay.adapters.CardReaderFailure
import com.payabli.sdk.taptopay.adapters.ReaderFailureKind
import com.payabli.sdk.taptopay.attestation.AttestationException
import com.payabli.sdk.taptopay.attestation.VerdictClass
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceClient
import com.payabli.sdk.taptopay.attestation.device.DeviceServiceException
import com.payabli.sdk.taptopay.attestation.device.FakeDeviceTransport
import com.payabli.sdk.taptopay.attestation.device.declineEnvelope
import com.payabli.sdk.taptopay.attestation.device.successEnvelope
import com.payabli.sdk.taptopay.attestation.impl.PlayIntegrityErrorMapping
import com.payabli.sdk.taptopay.session.TapToPayFailureReason
import com.payabli.sdk.taptopay.session.TapToPaySessionManager
import com.payabli.sdk.taptopay.session.TapToPaySessionState
import com.payabli.sdk.testutils.logging.RecordingSdkLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The card-present reporting sites on a build that did not link the telemetry artifact.
 *
 * **This module's classpath is that build.** `:taptopay` depends on `:core` and nothing else, so the
 * reporting module is genuinely absent here rather than mocked away — which is the configuration a
 * card-present integrator gets: the umbrella omits this artifact, so an app linking it has to add reporting
 * separately or not at all.
 *
 * These drive the real call sites rather than imitating the calls they make. Every one of them reports, and
 * every one of them has to behave identically with nothing listening: the route still answers, the failure
 * is still classified, and the caller cannot tell.
 */
class CardPresentReportsWithoutTelemetryTest {
    private val logger = RecordingSdkLogger()

    /** The premise the rest of this class rests on. If it ever fails, these tests stopped testing absence. */
    @Test
    fun theReportingModuleIsNotOnThisClasspath() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName(TelemetryBootstrap.IMPLEMENTATION)
        }
    }

    @Test
    fun `a device route that answers still answers, and reports nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport =
                FakeDeviceTransport.answering(successEnvelope("""{"challengeId":"c-1","challenge":"Y2g="}"""))

            val response =
                DeviceServiceClient(transport, logger)
                    .challenge(ENTRY)

            assertEquals("c-1", response.challengeId)
        }

    @Test
    fun `a refused device route still raises its own failure, and reports nothing`() =
        runTest(timeout = TEST_TIMEOUT) {
            val transport = FakeDeviceTransport.answering(declineEnvelope(400, "refused"))

            val failure =
                runCatching {
                    DeviceServiceClient(transport, logger).challenge(ENTRY)
                }.exceptionOrNull()

            // The type, not merely that something was thrown: a failure telemetry introduces satisfies
            // "not null" while the refusal this exists to preserve is gone.
            assertTrue("the route's own refusal did not survive: $failure", failure is DeviceServiceException)
        }

    /**
     * The quota signal, which is the one report with no other caller.
     *
     * `PlayIntegrityErrorMapping` exists to classify a platform failure, and reporting is something it does
     * on the way past. With nothing listening it still has to return the same disposition.
     */
    @Test
    fun theQuotaSignalStillClassifiesTheFailure() {
        val standard =
            PlayIntegrityErrorMapping.failureFor(
                StandardIntegrityErrorCode.TOO_MANY_REQUESTS,
                VerdictClass.STANDARD,
            )

        assertTrue("standard: $standard", standard is AttestationException.Throttled)
    }

    @Test
    fun theQuotaSignalClassifiesTheClassicShapeToo() {
        val classic =
            PlayIntegrityErrorMapping.failureFor(
                StandardIntegrityErrorCode.TOO_MANY_REQUESTS,
                VerdictClass.CLASSIC,
            )

        assertTrue("classic: $classic", classic is AttestationException.Throttled)
    }

    @Test
    fun `every card-present lifecycle report is silent, and none of them throws`() {
        val startedAt = System.nanoTime()
        val failure = CardReaderException.DeviceDenied(CardReaderFailure(ReaderFailureKind.DEVICE_DENIED, code = "677"))

        // Every method on the facade, because absence has to hold for all of them and not for the one
        // a later change happened to exercise.
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
        TapToPayReports.nfcFailed(ReaderFailureKind.DEVICE_DENIED, startedAt)
        TapToPayReports.sessionStateChanged(
            TapToPaySessionState.Idle,
            TapToPaySessionState.Failed(TapToPayFailureReason.DEVICE_INELIGIBLE),
        )
    }

    @Test
    fun `the session still moves with nothing listening`() {
        // The real call site, not the facade: the write funnel reports on every published move, so a
        // recorder that could throw would take the state machine with it.
        val manager = TapToPaySessionManager(logger)

        manager.advance(TapToPaySessionState.AttestingDevice)

        assertEquals(TapToPaySessionState.AttestingDevice, manager.state.value)
    }

    private companion object {
        val TEST_TIMEOUT = 5.seconds
        const val ENTRY = "a-test-entrypoint"
    }
}
