package com.payabli.sdk.core

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.info
import com.payabli.sdk.core.logging.warn
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The only writer of the one [SdkState] the SDK publishes.
 *
 * The sink is handed in rather than owned here, because the state outlives any one session: it is readable
 * before the first [PayabliSession.initialize] and it stays readable across a replacement. One session has
 * one machine, every machine writes that same sink, and nothing else writes it at all, so there is one source
 * of truth rather than a machine plus a copy kept in step by hand. iOS mirrors its internal state onto its
 * facade through a `syncPublished()` call every transition has to remember, and one forgotten call
 * desynchronizes the two.
 *
 * **A machine that has been finished never writes again**, and that is what makes a shared sink safe. The
 * rule is per machine rather than per published value: a successor has to be able to publish [SdkState.Ready]
 * over the terminal value its predecessor left, while the predecessor must not be able to publish over the
 * successor. A request already past the point where it decides the session is finished can suspend, and by
 * the time it resumes the host may have re-initialized; it then calls the listener it was built with, which
 * belongs to the machine it started under.
 *
 * Both transitions are reachable and tested, which is the bar: iOS documents a terminal state whose only
 * trigger has no call site, and a state nothing can reach is worse than no state, because a host writes a
 * recovery branch that never runs.
 */
internal class SessionStateMachine(
    private val sink: MutableStateFlow<SdkState> = MutableStateFlow(SdkState.Uninitialized),
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.CORE),
) {
    @Volatile
    private var finished = false

    /** True once this machine is finished, whether it published the fact or was retired by [finish]. */
    val isFinished: Boolean get() = finished

    /**
     * Retires this machine without publishing anything.
     *
     * For a session dropped without ever reaching the terminal state, where whatever replaces it publishes
     * its own value: a successor about to become ready, or a test putting the state back to
     * [SdkState.Uninitialized]. Publishing here as well would announce a terminal state that never became
     * true.
     */
    fun finish() {
        finished = true
    }

    /** `initialize` succeeded. */
    fun markReady() {
        transition(SdkState.Ready)
    }

    /**
     * Auth is terminally unrecoverable, so the host has to re-initialize.
     *
     * Idempotent, and deliberately so: several in-flight requests can each discover the same dead session,
     * and the second one to notice is reporting the same fact rather than a new one.
     */
    fun markReinitializeRequired() {
        transition(SdkState.ReinitializeRequired)
    }

    /**
     * Decides against the value it then swaps, so a refused transition is never briefly published.
     *
     * Writing first and restoring afterwards would be simpler and wrong: a `StateFlow` collector is woken by
     * the write, so the reverted value can still be observed. The loop re-reads only when a concurrent caller
     * won the swap, which is the ordinary compare-and-set retry rather than a special case.
     *
     * The finished check sits **inside** the loop for the same reason the swap does. A caller that reads the
     * flag as false, is descheduled, and wakes after a successor published loses its swap, comes back around,
     * and finds itself finished. Read once above the loop, that caller would retry straight into a write.
     *
     * The flag is set **after** the swap, never before, so a machine `install` sees as finished has already
     * finished writing and cannot land a value on top of the successor install is about to build.
     */
    private fun transition(target: SdkState) {
        while (true) {
            if (finished) {
                // Silent for the terminal target: several in-flight requests discovering the same dead
                // session is the documented case, not an anomaly. Anything else is an attempt to revive.
                if (target != SdkState.ReinitializeRequired) {
                    logger.warn(
                        LogField.safe("event", "session_state_refused"),
                        LogField.safe("state", target.diagnosticName),
                    ) { "refused a transition out of the terminal state" }
                }
                return
            }

            val previous = sink.value
            if (previous == target) return

            if (sink.compareAndSet(previous, target)) {
                if (target == SdkState.ReinitializeRequired) finished = true
                logger.info(
                    LogField.safe("event", "session_state"),
                    LogField.safe("state", target.diagnosticName),
                ) { "session state changed" }
                return
            }
        }
    }
}

/**
 * The name for a log record. An exhaustive `when` rather than `simpleName`, so adding a state fails to
 * compile here instead of emitting a name R8 is free to rewrite.
 */
private val SdkState.diagnosticName: String
    get() =
        when (this) {
            SdkState.Uninitialized -> "uninitialized"
            SdkState.Ready -> "ready"
            SdkState.ReinitializeRequired -> "reinitialize_required"
        }
