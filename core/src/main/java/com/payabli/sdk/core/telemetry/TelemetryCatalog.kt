package com.payabli.sdk.core.telemetry

import androidx.annotation.RestrictTo

/**
 * Which property keys each event may carry, and the gate that holds an event to it.
 *
 * **Deny by default, applied before anything is retained.** An event this table does not name is dropped
 * whole, and a key an event does not declare is dropped from it, so nothing unvetted is ever queued or
 * written down. A caller cannot widen either by passing more, and widening a row is a reviewed change to this
 * file.
 *
 * That order matters more than the contents: a scrub that ran on the way out would leave the unscrubbed value
 * sitting in memory, and on disk if the queue ever spills, for as long as the batch waited.
 *
 * The table is the same one the other platform holds. Both bind to one catalog, so a row added here without a
 * counterpart there is drift.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object TelemetryCatalog {
    /** Longest property key that may be reported. */
    internal const val MAX_KEY_LENGTH: Int = 64

    /** Longest property value that may be reported. */
    internal const val MAX_VALUE_LENGTH: Int = 256

    /** Most properties one event may carry. */
    internal const val MAX_PROPERTIES: Int = 20

    /**
     * The events that mark a flow beginning. They carry nothing.
     *
     * A start is located by its own name and by the session it belongs to, which every event already
     * carries. There is no per-flow handle: `sessionId` spans one SDK lifetime and joins every event in it,
     * and joining across a process restart is the service's to answer rather than the client's to invent.
     */
    private val NONE = emptySet<String>()

    private val TIMED = setOf(TelemetryProperties.DURATION_MS)

    private val TIMED_OUTCOME =
        setOf(
            TelemetryProperties.OUTCOME,
            TelemetryProperties.CODE,
            TelemetryProperties.DURATION_MS,
        )

    private val ROUTE = TIMED_OUTCOME + TelemetryProperties.ATTEMPT

    /**
     * The whole vocabulary, event by event.
     *
     * `sdk.telemetryDisabled` is absent. It is declared in [TelemetryEvents] so that neither
     * platform reintroduces the name, and leaving it out here is what makes the promise that it is never
     * reported hold even if a call site is written.
     */
    private val ALLOWED: Map<String, Set<String>> =
        mapOf(
            TelemetryEvents.TOKENIZATION_STARTED to NONE,
            TelemetryEvents.TOKENIZATION_SUCCEEDED to TIMED,
            TelemetryEvents.TOKENIZATION_FAILED to TIMED_OUTCOME,
            TelemetryEvents.TOKENIZATION_CANCELLED to TIMED,
            TelemetryEvents.FORM_PRESENTED to setOf(TelemetryProperties.STEP),
            TelemetryEvents.FORM_VALIDATION_ERROR to
                setOf(TelemetryProperties.REASON, TelemetryProperties.ATTEMPT),
            TelemetryEvents.TTP_INITIALIZE_STARTED to NONE,
            TelemetryEvents.TTP_INITIALIZE_SUCCEEDED to TIMED,
            TelemetryEvents.TTP_INITIALIZE_FAILED to TIMED_OUTCOME,
            TelemetryEvents.TTP_ATTESTATION_STARTED to NONE,
            TelemetryEvents.TTP_ATTESTATION_SUCCEEDED to TIMED,
            TelemetryEvents.TTP_ATTESTATION_FAILED to TIMED_OUTCOME,
            TelemetryEvents.TTP_CHARGE_STARTED to NONE,
            TelemetryEvents.TTP_CHARGE_SUCCEEDED to TIMED,
            TelemetryEvents.TTP_CHARGE_FAILED to TIMED_OUTCOME,
            TelemetryEvents.TTP_NFC_STARTED to NONE,
            TelemetryEvents.TTP_NFC_SUCCEEDED to TIMED,
            TelemetryEvents.TTP_NFC_FAILED to
                setOf(
                    TelemetryProperties.OUTCOME,
                    TelemetryProperties.REASON,
                    TelemetryProperties.DURATION_MS,
                ),
            TelemetryEvents.TTP_REINITIALIZE_STARTED to NONE,
            TelemetryEvents.TTP_REINITIALIZE_SUCCEEDED to TIMED,
            TelemetryEvents.TTP_SESSION_STATE_CHANGED to
                setOf(TelemetryProperties.FROM, TelemetryProperties.TO, TelemetryProperties.REASON),
            TelemetryEvents.TTP_DEVICE_CHALLENGE_COMPLETED to ROUTE,
            TelemetryEvents.TTP_DEVICE_REGISTER_COMPLETED to ROUTE,
            TelemetryEvents.TTP_DEVICE_ATTEST_COMPLETED to ROUTE,
            TelemetryEvents.TTP_DEVICE_ACTIVATE_COMPLETED to ROUTE,
            TelemetryEvents.TTP_DEVICE_CONFIG_COMPLETED to ROUTE,
            TelemetryEvents.TTP_ATTESTATION_QUOTA_EXHAUSTED to
                setOf(
                    TelemetryProperties.CODE,
                    TelemetryProperties.REASON,
                    TelemetryProperties.ATTEMPT,
                ),
            TelemetryEvents.PAYIN_CAPTURE_COMPLETED to TIMED_OUTCOME,
            TelemetryEvents.PAYIN_AUTHORIZE_COMPLETED to TIMED_OUTCOME,
            TelemetryEvents.PAYIN_STORE_METHOD_COMPLETED to TIMED_OUTCOME,
            TelemetryEvents.SDK_INITIALIZE_STARTED to setOf(TelemetryProperties.STATE),
            TelemetryEvents.SDK_INITIALIZE_FAILED to
                setOf(
                    TelemetryProperties.OUTCOME,
                    TelemetryProperties.CODE,
                    TelemetryProperties.REASON,
                    TelemetryProperties.DURATION_MS,
                ),
            TelemetryEvents.SDK_INITIALIZED to setOf(TelemetryProperties.STATE),
            TelemetryEvents.AUTH_TOKEN_ACQUIRED to setOf(TelemetryProperties.DURATION_MS),
            TelemetryEvents.AUTH_TOKEN_FAILED to
                setOf(
                    TelemetryProperties.OUTCOME,
                    TelemetryProperties.REASON,
                    TelemetryProperties.DURATION_MS,
                ),
            TelemetryEvents.AUTH_TOKEN_REFRESHED to
                setOf(TelemetryProperties.DURATION_MS, TelemetryProperties.ATTEMPT),
        )

    /** Every event this catalog will report. Test-facing; the gate is [scrub]. */
    internal val events: Set<String> get() = ALLOWED.keys

    /** The keys [event] may carry, empty when the event is not one this catalog reports. */
    internal fun allowedKeys(event: String): Set<String> = ALLOWED[event].orEmpty()

    /**
     * [properties] reduced to what [event] may report, or `null` when the event may not be reported at all.
     *
     * A pair is dropped rather than corrected: a value too long or carrying anything outside printable ASCII
     * is one this SDK did not mean to send, and truncating it would keep a record that no longer says what
     * the call site meant.
     */
    public fun scrub(
        event: String,
        properties: Map<String, String>,
    ): Map<String, String>? {
        val allowed = ALLOWED[event] ?: return null
        if (properties.isEmpty()) return emptyMap()

        return properties
            .asSequence()
            .filter { (key, value) -> key in allowed && isReportable(key, value) }
            .take(MAX_PROPERTIES)
            .associate { it.key to it.value }
    }

    private fun isReportable(
        key: String,
        value: String,
    ): Boolean =
        key.length <= MAX_KEY_LENGTH &&
            value.isNotEmpty() &&
            value.length <= MAX_VALUE_LENGTH &&
            value.all { it in ' '..'~' }
}
