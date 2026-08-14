package com.payabli.sdk.taptopay.session

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
 * The one writer of a card-present session's state.
 *
 * The sink is owned here rather than handed in, unlike the core session's: this state begins when a session
 * is built and has nothing to say before that, so there is no reader to serve earlier.
 *
 * **No mutator returns a value a caller can drop.** The sibling SDK's transition returns a boolean that
 * every one of its call sites discards, and the cost was a shipped defect: after an expiry its narrow table
 * refused every move, so a full re-initialization ran every phase, reported success, and left the state
 * where it started. [advance] closes that by owning both halves. Entering a phase without moving the state
 * is not something a caller can express.
 *
 * The rule for a refused move is stated once: [advance] throws, because it runs inside a serialized region
 * that starts from a known state, so a refusal there is a defect in this SDK's own sequence. [invalidate]
 * logs and returns, because it is the one mutator a reader callback can reach from outside that region and
 * it can legitimately lose a race.
 */
internal class TapToPaySessionManager(
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) {
    /**
     * Holds the decision and the write it depends on together.
     *
     * Writing first and reverting afterwards would be simpler and wrong: a `StateFlow` collector is woken by
     * the write, so the reverted value can still be observed.
     */
    private val guard = Any()

    private val sink = MutableStateFlow<TapToPaySessionState>(TapToPaySessionState.Idle)

    /** Where the session has got to. Conflated, so a collector joining late sees the current value. */
    val state: StateFlow<TapToPaySessionState> = sink.asStateFlow()

    /**
     * Moves to [to] and then runs [work] under it.
     *
     * The order is the point. The state moves first, so a phase that runs has always been announced, and a
     * phase that cannot be announced does not run: a refused move throws before [work] is reached, when
     * nothing has happened yet.
     *
     * This does not serialize anything. Two callers advancing at once would interleave their phases, which
     * is what the region in [TapToPaySessionCoordinator] exists to prevent.
     */
    suspend fun <T> advance(
        to: TapToPaySessionState,
        work: suspend () -> T,
    ): T {
        check(write(to)) {
            // A defect in this SDK's own sequence rather than anything a host did, so it is not part of the
            // failure vocabulary a caller handles. Both names are from the fixed state vocabulary.
            "a session cannot move to ${to.diagnosticName} from ${state.value.diagnosticName}"
        }
        return work()
    }

    /**
     * Moves to [to] with nothing to run under it.
     *
     * For the states a run passes through or ends on rather than works in. It throws on refusal for the same
     * reason the other overload does: reporting a session ready while it stands somewhere else is the defect
     * this type exists to prevent.
     */
    fun advance(to: TapToPaySessionState) {
        check(write(to)) {
            "a session cannot move to ${to.diagnosticName} from ${state.value.diagnosticName}"
        }
    }

    /**
     * Puts the session back to the start.
     *
     * The first act of building a session, whatever the caller left behind, because the table is narrow and
     * every phase after this one would otherwise be refused.
     */
    fun reset() {
        write(TapToPaySessionState.Idle)
    }

    /**
     * Records that the reader session behind a ready state is spent.
     *
     * Refusal is expected rather than exceptional: the caller is a reader whose failure may arrive after the
     * session it belonged to was already replaced or torn down, so a move that is no longer legal is a stale
     * report and not a defect. It is logged and dropped.
     */
    fun invalidate() {
        write(TapToPaySessionState.SessionExpired)
    }

    /**
     * The last write of a run, when the run did not get where it was going.
     *
     * Logs a refusal instead of throwing, because this is reached from a failure path: throwing here would
     * replace the failure a caller is about to be given with one about bookkeeping.
     */
    fun settle(to: TapToPaySessionState) {
        write(to)
    }

    /**
     * Decides and writes under [guard]. Both records are emitted after the monitor is released, because a
     * collector on an immediate dispatcher resumes inside the write, so whatever is held here is held while
     * foreign code runs.
     */
    private fun write(to: TapToPaySessionState): Boolean {
        val from: TapToPaySessionState
        val permitted: Boolean
        val published: Boolean

        synchronized(guard) {
            from = sink.value
            permitted = TapToPaySessionTransitions.permits(from, to)
            published = permitted && from != to
            if (published) {
                sink.value = to
            }
        }

        if (!permitted) {
            logger.warn(
                LogField.safe("event", "ttp_session_state_refused"),
                LogField.safe("fromstate", from.diagnosticName),
                LogField.safe("tostate", to.diagnosticName),
            ) { "refused a session state change" }
        }
        if (published) {
            logger.info(
                LogField.safe("event", "ttp_session_state"),
                LogField.safe("state", to.diagnosticName),
                LogField.safe("errorkind", (to as? TapToPaySessionState.Failed)?.reason?.name),
            ) { "session state changed" }
        }
        return permitted
    }
}
