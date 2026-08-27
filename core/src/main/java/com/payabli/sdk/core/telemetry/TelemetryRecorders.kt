package com.payabli.sdk.core.telemetry

import androidx.annotation.RestrictTo
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.warn

/**
 * The one installed [TelemetryRecorder], and the call every emitting site actually makes.
 *
 * A small service locator, the same shape as `LoggerRegistry` and for the same reason: a dependency-injection
 * framework is barred, and a capability that had to be handed a recorder would need one threaded through
 * every constructor between the session and the call site.
 *
 * **Nothing is installed by default, and that is the whole design.** Absent an installed recorder every
 * [record] is a field read and a return, so a host that never links the telemetry module pays nothing and
 * every emitting call site is written the same way whether one is linked or not.
 *
 * [record] is `inline`, so [current] and [refused] are part of this artifact's binary surface even though a
 * host cannot name them: an inlined body is copied into every calling module and has to be able to reach what
 * it uses. What that costs is that changing either signature needs the calling modules rebuilt, which is what
 * `:payabli-bom` is for. What it buys is that an app without the telemetry module allocates nothing at all.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object TelemetryRecorders {
    private val logger get() = LoggerRegistry.of(LogCategory.TELEMETRY)

    @Volatile
    private var installed: TelemetryRecorder? = null

    /**
     * Installs [recorder], replacing whatever was there.
     *
     * Replacing rather than refusing: a session re-initialized after a terminal failure installs again, and
     * refusing the second install would leave the new session reporting through the dead one's queue.
     */
    public fun install(recorder: TelemetryRecorder) {
        installed = recorder
    }

    /** Removes the installed recorder, so [record] goes back to costing nothing. */
    public fun clear() {
        installed = null
    }

    /**
     * Records [event] as belonging to [session] rather than to whatever is installed now.
     *
     * For an operation reported when it finishes, which can be after a re-initialize has replaced the session
     * it began under. A recorder that is not [SessionScopedRecorder] still receives the event and its
     * properties; only the session is dropped.
     */
    public inline fun recordFor(
        session: TelemetrySessionContext,
        event: String,
        properties: () -> Map<String, String> = { emptyMap() },
    ) {
        // The opt-out is the one that was in force when the operation ran. A session with reporting off
        // installs no recorder, so nothing of its own can reach here; a successor that enabled it does have
        // one, and without this the earlier session's work would leave on it.
        if (!session.telemetryEnabled) return

        val recorder = current() ?: return
        try {
            if (recorder is SessionScopedRecorder) {
                recorder.record(event, properties(), session)
            } else {
                recorder.record(event, properties())
            }
        } catch (failure: RuntimeException) {
            refused(failure)
        }
    }

    /**
     * The installed recorder, read once per [record].
     *
     * Reachable because [record] is inlined into other modules. Nothing else should call it: a caller that
     * held the recorder itself would keep a dead one alive across a re-initialize.
     */
    @PublishedApi
    internal fun current(): TelemetryRecorder? = installed

    /** Records that a recorder threw. Reachable for the reason [current] is. */
    @PublishedApi
    internal fun refused(failure: RuntimeException) {
        logger.warn(
            LogField.safe("event", "telemetry_record_failed"),
            LogField.safe("errorKind", failure.javaClass.simpleName),
        ) { "recorder refused an event" }
    }

    /**
     * Hands [event] to the installed recorder, or does nothing.
     *
     * **[properties] is only built if something is listening.** A map assembled and then dropped is what an
     * app that never linked the module would pay on every instrumented operation, and this seam is what every
     * future call site uses, including ones on paths that make no request at all.
     *
     * **Failure is swallowed here**, which is the guarantee that lets a payment path call this on its
     * critical line. A reporting channel that can fail a charge is worse than no reporting channel.
     * An `Error` is not caught: an exhausted heap is not this layer's to absorb.
     */
    public inline fun record(
        event: String,
        properties: () -> Map<String, String> = { emptyMap() },
    ) {
        val recorder = current() ?: return
        try {
            recorder.record(event, properties())
        } catch (failure: RuntimeException) {
            refused(failure)
        }
    }
}
