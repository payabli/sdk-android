package com.payabli.sdk.core

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.info
import com.payabli.sdk.core.logging.warn
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The only writer of the one [SdkState] the SDK publishes, into a sink handed in because it outlives any session.
 * **A finished machine never writes again**: a successor may publish [SdkState.Ready] over its predecessor's
 * terminal value, and a request that resumes under the predecessor cannot publish over the successor.
 */
internal class SessionStateMachine(
    private val sink: MutableStateFlow<SdkState> = MutableStateFlow(SdkState.Uninitialized),
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.CORE),
) {
    /**
     * Holds [finished] and the write it explains together, so the two are one step to any observer.
     *
     * Neither order works on its own. Raise the flag after the write and a caller that reacts to the terminal
     * value still reads this machine as usable, so `install` hands back the session the host was just told to
     * replace. Raise it before and `install` can build a successor while this machine is still between the
     * two lines, so the write lands on top of the successor's. Under one monitor the question does not
     * arise: a caller reading [isFinished] waits for the write to complete, and the write cannot start once
     * the flag is up.
     */
    private val guard = Any()

    private var finished = false

    /** True once this machine is finished, whether it published the fact or was retired by [finish]. */
    val isFinished: Boolean get() = synchronized(guard) { finished }

    /**
     * Retires this machine without publishing anything.
     *
     * For a session dropped without ever reaching the terminal state, where whatever replaces it publishes
     * its own value: a successor about to become ready, or a test putting the state back to
     * [SdkState.Uninitialized]. Publishing here as well would announce a terminal state that never became
     * true.
     */
    fun finish() {
        synchronized(guard) { finished = true }
    }

    /** `initialize` succeeded. */
    fun markReady() {
        transition(SdkState.Ready)
    }

    /**
     * Auth is terminally unrecoverable, so the host has to re-initialize.
     *
     * Idempotent: several in-flight requests can each discover the same dead session,
     * and the second one to notice is reporting the same fact rather than a new one.
     */
    fun markReinitializeRequired() {
        transition(SdkState.ReinitializeRequired)
    }

    /**
     * Decides and writes under [guard], so a refused transition is never briefly published and no caller can
     * read the value without the flag that explains it.
     *
     * Writing first and reverting afterwards would be simpler and wrong: a `StateFlow` collector is woken by
     * the write, so the reverted value can still be observed.
     *
     * Both records are emitted after the monitor is released. A collector on an immediate dispatcher resumes
     * inside the write, so whatever is held here is held while foreign code runs.
     */
    private fun transition(target: SdkState) {
        var refused = false
        var published = false

        synchronized(guard) {
            if (finished) {
                // Silent for the terminal target: several in-flight requests discovering the same dead
                // session is the documented case, not an anomaly. Anything else is an attempt to revive.
                refused = target != SdkState.ReinitializeRequired
            } else if (sink.value != target) {
                if (target == SdkState.ReinitializeRequired) finished = true
                sink.value = target
                published = true
            }
        }

        if (refused) {
            logger.warn(
                LogField.safe("event", "session_state_refused"),
                LogField.safe("state", target.diagnosticName),
            ) { "refused a transition out of the terminal state" }
        }
        if (published) {
            logger.info(
                LogField.safe("event", "session_state"),
                LogField.safe("state", target.diagnosticName),
            ) { "session state changed" }
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
