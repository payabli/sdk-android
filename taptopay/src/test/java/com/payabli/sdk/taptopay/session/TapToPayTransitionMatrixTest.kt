package com.payabli.sdk.taptopay.session

import com.payabli.sdk.taptopay.session.TapToPaySessionState.AttestingDevice
import com.payabli.sdk.taptopay.session.TapToPaySessionState.Failed
import com.payabli.sdk.taptopay.session.TapToPaySessionState.FetchingConfig
import com.payabli.sdk.taptopay.session.TapToPaySessionState.Idle
import com.payabli.sdk.taptopay.session.TapToPaySessionState.InitializingReader
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
 * An exhaustive `when`, so a new state fails to compile here.
 */
private fun legalTargetsFrom(from: TapToPaySessionState): Set<TapToPaySessionState> =
    when (from) {
        Idle -> setOf(Idle, AttestingDevice, FetchingConfig, FAILED_INTERNAL)
        AttestingDevice -> setOf(Idle, AttestingDevice, FetchingConfig, PENDING, FAILED_INTERNAL)
        FetchingConfig -> setOf(Idle, FetchingConfig, InitializingReader, PENDING, FAILED_INTERNAL)
        InitializingReader -> setOf(Idle, InitializingReader, Ready, FAILED_INTERNAL)
        Ready -> setOf(Idle, Ready, CHARGING, SessionExpired, FAILED_INTERNAL)
        is TapToPaySessionState.Charging -> setOf(Idle, Ready, CHARGING, SessionExpired, FAILED_INTERNAL)
        SessionExpired -> setOf(Idle, SessionExpired, Reinitializing, FAILED_INTERNAL)
        Reinitializing -> setOf(Idle, Reinitializing, FetchingConfig, FAILED_INTERNAL)
        is TapToPaySessionState.PendingActivation -> setOf(Idle, PENDING, AttestingDevice, FAILED_INTERNAL)
        is Failed -> setOf(Idle, AttestingDevice, FetchingConfig, FAILED_INTERNAL)
    }

private val FAILED_INTERNAL = Failed(TapToPayFailureReason.SDK_INTERNAL_ERROR)

private val PENDING = EVERY_SESSION_STATE.single { it is TapToPaySessionState.PendingActivation }

private val CHARGING = EVERY_SESSION_STATE.single { it is TapToPaySessionState.Charging }

class TapToPayTransitionMatrixTest {
    @Test
    fun `the table names every state`() {
        assertEquals(EVERY_SESSION_STATE.size, EVERY_SESSION_STATE.distinct().size)
        assertEquals(10, EVERY_SESSION_STATE.size)
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
    fun `a charge moves forward through its activities and never back`() {
        val forward =
            setOf(
                TapToPayChargeActivity.OPENING to TapToPayChargeActivity.WAITING_FOR_CARD,
                TapToPayChargeActivity.WAITING_FOR_CARD to TapToPayChargeActivity.CLOSING,
            )
        for (from in TapToPayChargeActivity.entries) {
            for (to in TapToPayChargeActivity.entries) {
                assertEquals(
                    "$from -> $to",
                    from == to || (from to to) in forward,
                    TapToPaySessionTransitions.permits(
                        TapToPaySessionState.Charging(from),
                        TapToPaySessionState.Charging(to),
                    ),
                )
            }
        }
    }

    @Test
    fun `every activity can end the charge`() {
        for (activity in TapToPayChargeActivity.entries) {
            val charging = TapToPaySessionState.Charging(activity)
            assertEquals("$activity -> ready", true, TapToPaySessionTransitions.permits(charging, Ready))
            assertEquals("$activity -> expired", true, TapToPaySessionTransitions.permits(charging, SessionExpired))
        }
    }

    @Test
    fun `a charge starts by opening`() {
        for (activity in TapToPayChargeActivity.entries) {
            assertEquals(
                "ready -> $activity",
                activity == TapToPayChargeActivity.OPENING,
                TapToPaySessionTransitions.permits(Ready, TapToPaySessionState.Charging(activity)),
            )
        }
    }

    @Test
    fun `a failure may change its reason`() {
        assertEquals(
            true,
            TapToPaySessionTransitions.permits(
                Failed(TapToPayFailureReason.SERVICE_UNAVAILABLE),
                Failed(TapToPayFailureReason.DEVICE_SETUP_REQUIRED),
            ),
        )
    }
}
