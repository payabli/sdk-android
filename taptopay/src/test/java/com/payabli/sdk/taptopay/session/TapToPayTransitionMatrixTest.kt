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

/** One state per member, so every ordered pair below is a real pair. */
private val EVERY_STATE: List<TapToPaySessionState> =
    listOf(
        Idle,
        AttestingDevice,
        FetchingConfig,
        InitializingReader,
        Ready,
        SessionExpired,
        Reinitializing,
        PendingActivation,
        Failed(TapToPayFailureReason.INTERNAL),
    )

/**
 * The whole table, restated.
 *
 * Each row is the **complete** set of targets that source accepts, written out. That includes the three
 * rules the implementation states once — re-entering the current state, starting over, and failing — so
 * deleting one of those rules from the implementation fails a row here.
 *
 * An exhaustive `when`, so a tenth state fails to compile here rather than going untested.
 */
private fun legalTargetsFrom(from: TapToPaySessionState): Set<TapToPaySessionState> =
    when (from) {
        Idle -> setOf(Idle, AttestingDevice, FetchingConfig, FAILED)
        AttestingDevice -> setOf(Idle, AttestingDevice, FetchingConfig, PendingActivation, FAILED)
        FetchingConfig -> setOf(Idle, FetchingConfig, InitializingReader, PendingActivation, FAILED)
        InitializingReader -> setOf(Idle, InitializingReader, Ready, FAILED)
        Ready -> setOf(Idle, Ready, SessionExpired, FAILED)
        SessionExpired -> setOf(Idle, SessionExpired, Reinitializing, FAILED)
        Reinitializing -> setOf(Idle, Reinitializing, FetchingConfig, FAILED)
        PendingActivation -> setOf(Idle, PendingActivation, AttestingDevice, FAILED)
        is Failed -> setOf(Idle, AttestingDevice, FetchingConfig, FAILED)
    }

private val FAILED = Failed(TapToPayFailureReason.INTERNAL)

class TapToPayTransitionMatrixTest {
    @Test
    fun `the table names every state`() {
        assertEquals(EVERY_STATE.size, EVERY_STATE.distinct().size)
        assertEquals(9, EVERY_STATE.size)
    }

    @Test
    fun `every ordered pair is decided as the table says`() {
        for (from in EVERY_STATE) {
            val legal = legalTargetsFrom(from)
            for (to in EVERY_STATE) {
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
        for (from in EVERY_STATE) {
            assertEquals(from.diagnosticName, true, TapToPaySessionTransitions.permits(from, Idle))
        }
    }

    @Test
    fun `failing is reachable from every state`() {
        for (from in EVERY_STATE) {
            assertEquals(from.diagnosticName, true, TapToPaySessionTransitions.permits(from, FAILED))
        }
    }

    @Test
    fun `re-entering the current state is permitted from every state`() {
        for (from in EVERY_STATE) {
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
