package com.payabli.sdk.core

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.info
import com.payabli.sdk.core.logging.warn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the one [SdkState] a session publishes.
 *
 * The session exposes [state] read-only and this type is the only writer, so there is one source of truth
 * rather than a machine plus a copy kept in step by hand. iOS mirrors its internal state onto its facade
 * through a `syncPublished()` call every transition has to remember, and one forgotten call desynchronizes
 * the two.
 *
 * Both transitions are reachable and tested, which is the bar: iOS documents a terminal state whose only
 * trigger has no call site, and a state nothing can reach is worse than no state, because a host writes a
 * recovery branch that never runs.
 */
internal class SessionStateMachine(
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.CORE),
) {
    private val sink = MutableStateFlow<SdkState>(SdkState.Uninitialized)

    val state: StateFlow<SdkState> = sink.asStateFlow()

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
     */
    private fun transition(target: SdkState) {
        while (true) {
            val previous = sink.value
            if (previous == target) return

            // Refused, not merely unexpected: reviving in place would present a session the host has been
            // told to replace as usable again. No caller in `:core` produces this order.
            if (previous == SdkState.ReinitializeRequired) {
                logger.warn(
                    LogField.safe("event", "session_state_refused"),
                    LogField.safe("state", target.diagnosticName),
                ) { "refused a transition out of the terminal state" }
                return
            }

            if (sink.compareAndSet(previous, target)) {
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
