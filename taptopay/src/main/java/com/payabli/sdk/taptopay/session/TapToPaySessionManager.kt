package com.payabli.sdk.taptopay.session

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.info
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.taptopay.telemetry.TapToPayReports
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one writer of a card-present session's state.
 *
 * The sink is owned here. This state begins when a session is built and has nothing to say before that.
 *
 * **No mutator returns a value a caller can drop.** [advance] owns both halves, so entering a phase without
 * moving the state cannot be expressed.
 *
 * A refused move throws from [advance], which runs inside a serialized region that starts from a known
 * state, so a refusal there is a defect in this SDK's own sequence. [invalidate] logs and returns: it is
 * the one mutator a reader callback reaches from outside that region, and it can lose a race legitimately.
 */
internal class TapToPaySessionManager(
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) {
    /**
     * Holds the decision and the write it depends on together.
     *
     * A `StateFlow` collector is woken by the write, so a value written and then reverted is still observed.
     */
    private val guard = Any()

    private val sink = MutableStateFlow<TapToPaySessionState>(TapToPaySessionState.Idle)

    /** Where the session has got to. Conflated, so a collector joining late sees the current value. */
    val state: StateFlow<TapToPaySessionState> = sink.asStateFlow()

    private val readySink = MutableStateFlow(false)

    /**
     * Whether a payment can be taken right now.
     *
     * Its own flow, written wherever [state] is written, so the two cannot fall out of step. Deriving it
     * with `stateIn` would need a scope, and a scope handed in from outside is a coroutine that never
     * completes.
     */
    val isReady: StateFlow<Boolean> = readySink.asStateFlow()

    /**
     * Moves to [to] and then runs [work] under it.
     *
     * The state moves first, so a phase that runs has always been announced. A refused move throws before
     * [work] is reached, when nothing has happened yet.
     *
     * This serializes nothing. The region in [TapToPaySessionCoordinator] is what keeps two callers from
     * interleaving their phases.
     */
    suspend fun <T> advance(
        to: TapToPaySessionState,
        work: suspend () -> T,
    ): T {
        writeOrThrow(to)
        return work()
    }

    /**
     * Moves to [to] with nothing to run under it.
     *
     * For the states a run passes through or ends on. It throws on refusal for the reason the other overload
     * does: a session reported ready while it stands somewhere else is the defect this type prevents.
     */
    fun advance(to: TapToPaySessionState) {
        writeOrThrow(to)
    }

    /**
     * Writes, or throws naming the state the refusal was decided against.
     *
     * That state comes back from [write] rather than being read again. A second read happens outside the
     * monitor, so a concurrent write lands between the two and the message names a state that had nothing to
     * do with the refusal.
     *
     * A defect in this SDK's own sequence, so it is outside the failure vocabulary a caller handles. Both
     * names come from the fixed state vocabulary.
     */
    private fun writeOrThrow(to: TapToPaySessionState) {
        val written = write(to)
        check(written.permitted) {
            "a session cannot move to ${to.diagnosticName} from ${written.from.diagnosticName}"
        }
    }

    /**
     * Puts the session back to the start.
     *
     * The first act of building a session, whatever the caller left behind, since the table is narrow.
     *
     * Throws on a refusal, like the two [advance] overloads and for the same reason: starting over is
     * reachable from every state, so a refusal here is a broken table and the build that follows would run
     * every phase from a state nobody expects.
     */
    fun reset() {
        writeOrThrow(TapToPaySessionState.Idle)
    }

    /**
     * Records that the reader session behind a ready state is spent.
     *
     * Refusal is expected here. The caller is a reader whose failure can arrive after the session it belonged
     * to was replaced or torn down, so an illegal move is a stale report. It is logged and dropped.
     */
    fun invalidate() {
        write(TapToPaySessionState.SessionExpired)
    }

    /**
     * The last write of a run, when the run did not get where it was going.
     *
     * Logs a refusal, since this is reached from a failure path and a throw here would replace the failure a
     * caller is about to be given.
     */
    fun settle(to: TapToPaySessionState) {
        write(to)
    }

    /**
     * Decides, reports and writes under [guard].
     *
     * The report comes before the writes, so a collector that transitions from inside `sink.value = to`
     * cannot report its move before this one. Nothing foreign runs under the monitor at that point: the
     * recorder returns immediately and never throws, and a collector resumes only on the write after it.
     *
     * The refusal record is outside, where it publishes nothing.
     */
    private fun write(to: TapToPaySessionState): Written {
        val from: TapToPaySessionState
        val permitted: Boolean
        val published: Boolean

        synchronized(guard) {
            from = sink.value
            permitted = TapToPaySessionTransitions.permits(from, to)
            published = permitted && from != to
            if (published) {
                // Reported before the flows are written, so this move is reported before any move a
                // collector makes from inside that write. Nothing foreign has run yet: a collector
                // resumes on the write below, and the recorder returns immediately and never throws.
                //
                // Here rather than at the nine callers: this is the one place a move is decided, so a
                // state added later reports without anyone remembering to add it.
                logger.info(
                    LogField.safe("event", "ttp_session_state"),
                    LogField.safe("state", to.diagnosticName),
                    LogField.safe("errorkind", (to as? TapToPaySessionState.Failed)?.reason?.name),
                ) { "session state changed" }
                TapToPayReports.sessionStateChanged(from, to)

                // Readiness first: `sink.value = to` resumes an unconfined collector, which reads
                // [isReady] on the state it was just handed.
                readySink.value = to == TapToPaySessionState.Ready
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
        return Written(from, permitted)
    }

    /** What one write decided, so a caller naming the refusal does not read the state a second time. */
    private class Written(
        val from: TapToPaySessionState,
        val permitted: Boolean,
    )
}
