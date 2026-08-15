package com.payabli.sdk.taptopay.session

import com.payabli.sdk.taptopay.session.TapToPaySessionState.AttestingDevice
import com.payabli.sdk.taptopay.session.TapToPaySessionState.Failed
import com.payabli.sdk.taptopay.session.TapToPaySessionState.FetchingConfig
import com.payabli.sdk.taptopay.session.TapToPaySessionState.Idle
import com.payabli.sdk.taptopay.session.TapToPaySessionState.InitializingReader
import com.payabli.sdk.taptopay.session.TapToPaySessionState.PendingActivation
import com.payabli.sdk.taptopay.session.TapToPaySessionState.Ready
import com.payabli.sdk.taptopay.session.TapToPaySessionState.Reinitializing
import com.payabli.sdk.taptopay.session.TapToPaySessionState.SessionExpired
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The whole table, restated.
 *
 * Each row is the **complete** set of targets that source accepts, written out. That includes the three
 * rules the implementation states once — re-entering the current state, starting over, and failing — so
 * deleting one of those rules from the implementation fails a row here.
 *
 * An exhaustive `when`, so a tenth state fails to compile here.
 */
private fun legalTargetsFrom(from: TapToPaySessionState): Set<TapToPaySessionState> =
    when (from) {
        Idle -> setOf(Idle, AttestingDevice, FetchingConfig, FAILED_INTERNAL)
        AttestingDevice -> setOf(Idle, AttestingDevice, FetchingConfig, PendingActivation, FAILED_INTERNAL)
        FetchingConfig -> setOf(Idle, FetchingConfig, InitializingReader, PendingActivation, FAILED_INTERNAL)
        InitializingReader -> setOf(Idle, InitializingReader, Ready, FAILED_INTERNAL)
        Ready -> setOf(Idle, Ready, SessionExpired, FAILED_INTERNAL)
        SessionExpired -> setOf(Idle, SessionExpired, Reinitializing, FAILED_INTERNAL)
        Reinitializing -> setOf(Idle, Reinitializing, FetchingConfig, FAILED_INTERNAL)
        PendingActivation -> setOf(Idle, PendingActivation, AttestingDevice, FAILED_INTERNAL)
        is Failed -> setOf(Idle, AttestingDevice, FetchingConfig, FAILED_INTERNAL)
    }

private val FAILED_INTERNAL = Failed(TapToPayFailureReason.SDK_INTERNAL_ERROR)

class TapToPayTransitionMatrixTest {
    @Test
    fun `the table names every state`() {
        assertEquals(EVERY_SESSION_STATE.size, EVERY_SESSION_STATE.distinct().size)
        assertEquals(9, EVERY_SESSION_STATE.size)
    }

    @Test
    fun `every ordered pair is decided as the table says`() {
        for (from in EVERY_SESSION_STATE) {
            val legal = legalTargetsFrom(from)
            for (to in EVERY_SESSION_STATE) {
                assertEquals(
                    "${from.diagnosticName} -> ${to.diagnosticName}",
                    to in legal,
                    TapToPaySessionTransitions.permits(from, to),
                )
            }
        }
    }

    @Test
    fun `starting over is reachable from every state`() {
        for (from in EVERY_SESSION_STATE) {
            assertEquals(from.diagnosticName, true, TapToPaySessionTransitions.permits(from, Idle))
        }
    }

    @Test
    fun `failing is reachable from every state`() {
        for (from in EVERY_SESSION_STATE) {
            assertEquals(from.diagnosticName, true, TapToPaySessionTransitions.permits(from, FAILED_INTERNAL))
        }
    }

    @Test
    fun `re-entering the current state is permitted from every state`() {
        for (from in EVERY_SESSION_STATE) {
            assertEquals(from.diagnosticName, true, TapToPaySessionTransitions.permits(from, from))
        }
    }

    @Test
    fun `a failure may change its reason`() {
        assertEquals(
            true,
            TapToPaySessionTransitions.permits(
                Failed(TapToPayFailureReason.SERVICE_UNAVAILABLE),
                Failed(TapToPayFailureReason.ATTESTATION_REQUIRED),
            ),
        )
    }
}
