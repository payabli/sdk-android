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

    private val TIMED = setOf(TelemetryProperty.DURATION_MS.key)

    private val TIMED_OUTCOME =
        setOf(
            TelemetryProperty.OUTCOME.key,
            TelemetryProperty.CODE.key,
            TelemetryProperty.DURATION_MS.key,
        )

    /**
     * The device routes carry no attempt, and cannot.
     *
     * Nothing in the card-present client retries a route: a caller retries the whole enrolment, so the layer
     * that emits these has no attempt to count and no way to learn one. A key allowed here that no call site
     * can fill is a dimension a reader will look for and never find, which is worse than its absence.
     */
    private val ROUTE = TIMED_OUTCOME

    /**
     * The whole vocabulary, event by event.
     *
     * `sdk.telemetryDisabled` is absent. It is declared in [TelemetryEvents] so that neither
     * platform reintroduces the name, and leaving it out here is what makes the promise that it is never
     * reported hold even if a call site is written.
     */
    private val ALLOWED: Map<String, Set<String>> =
        mapOf(
            TelemetryEvents.FORM_PRESENTED to setOf(TelemetryProperty.STEP.key),
            TelemetryEvents.FORM_SUBMITTED to setOf(TelemetryProperty.STEP.key),
            // No attempt: nothing counts one.
            TelemetryEvents.FORM_VALIDATION_ERROR to
                setOf(TelemetryProperty.REASON.key, TelemetryProperty.FIELD.key),
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
            // Carries both, and the code is the half that earns its place. [TelemetryProperty.REASON] is the
            // reader's own classification, which is coarser than the vendor's code by design and is
            // `unclassified` for any refusal the mapping does not recognise. That is the case where the code
            // is the only information there is, and it is the case a reason alone withholds it.
            TelemetryEvents.TTP_NFC_FAILED to
                setOf(
                    TelemetryProperty.OUTCOME.key,
                    TelemetryProperty.REASON.key,
                    TelemetryProperty.CODE.key,
                    TelemetryProperty.DURATION_MS.key,
                ),
            TelemetryEvents.TTP_REINITIALIZE_STARTED to NONE,
            TelemetryEvents.TTP_REINITIALIZE_SUCCEEDED to TIMED,
            TelemetryEvents.TTP_SESSION_STATE_CHANGED to
                setOf(TelemetryProperty.FROM.key, TelemetryProperty.TO.key, TelemetryProperty.REASON.key),
            TelemetryEvents.TTP_DEVICE_CHALLENGE_COMPLETED to ROUTE,
            TelemetryEvents.TTP_DEVICE_REGISTER_COMPLETED to ROUTE,
            TelemetryEvents.TTP_DEVICE_ATTEST_COMPLETED to ROUTE,
            TelemetryEvents.TTP_DEVICE_ACTIVATE_COMPLETED to ROUTE,
            TelemetryEvents.TTP_DEVICE_CONFIG_COMPLETED to ROUTE,
            // No attempt: nothing counts one.
            TelemetryEvents.TTP_ATTESTATION_QUOTA_EXHAUSTED to
                setOf(TelemetryProperty.CODE.key, TelemetryProperty.REASON.key),
            TelemetryEvents.PAYIN_CAPTURE_COMPLETED to TIMED_OUTCOME,
            TelemetryEvents.PAYIN_AUTHORIZE_COMPLETED to TIMED_OUTCOME,
            TelemetryEvents.PAYIN_STORE_METHOD_COMPLETED to TIMED_OUTCOME,
            TelemetryEvents.PAYIN_VOID_COMPLETED to TIMED_OUTCOME,
            TelemetryEvents.SDK_INITIALIZE_STARTED to setOf(TelemetryProperty.STATE.key),
            TelemetryEvents.SDK_INITIALIZE_FAILED to
                setOf(
                    TelemetryProperty.OUTCOME.key,
                    TelemetryProperty.CODE.key,
                    TelemetryProperty.REASON.key,
                    TelemetryProperty.DURATION_MS.key,
                ),
            TelemetryEvents.SDK_INITIALIZED to setOf(TelemetryProperty.STATE.key),
            TelemetryEvents.AUTH_TOKEN_ACQUIRED to setOf(TelemetryProperty.DURATION_MS.key),
            TelemetryEvents.AUTH_TOKEN_FAILED to
                setOf(
                    TelemetryProperty.OUTCOME.key,
                    TelemetryProperty.REASON.key,
                    TelemetryProperty.DURATION_MS.key,
                ),
            TelemetryEvents.AUTH_TOKEN_REFRESHED to
                setOf(TelemetryProperty.DURATION_MS.key, TelemetryProperty.ATTEMPT.key),
        )

    /** The events whose name already means a failure, and which carry no outcome for [forcesSend] to read. */
    private val IMMEDIATE: Set<String> =
        setOf(
            TelemetryEvents.FORM_VALIDATION_ERROR,
            TelemetryEvents.TTP_INITIALIZE_FAILED,
            TelemetryEvents.TTP_ATTESTATION_FAILED,
            TelemetryEvents.TTP_CHARGE_FAILED,
            TelemetryEvents.TTP_NFC_FAILED,
            TelemetryEvents.TTP_ATTESTATION_QUOTA_EXHAUSTED,
            TelemetryEvents.SDK_INITIALIZE_FAILED,
            TelemetryEvents.AUTH_TOKEN_FAILED,
        )

    /** Every event this catalog will report. Test-facing; the gate is [scrub]. */
    internal val events: Set<String> get() = ALLOWED.keys

    /** Test-facing view of [IMMEDIATE]; the decision is [forcesSend]. */
    internal val immediateEvents: Set<String> get() = IMMEDIATE

    /**
     * Whether [event] should leave now rather than wait for a full batch or the next tick.
     *
     * A name in [IMMEDIATE], or an outcome that is not successful. Call it on **scrubbed** properties.
     */
    public fun forcesSend(
        event: String,
        properties: Map<String, String>,
    ): Boolean {
        if (event in IMMEDIATE) return true
        val outcome = properties[TelemetryProperty.OUTCOME.key] ?: return false
        return outcome !in TelemetryProperties.Outcome.SUCCESSFUL
    }

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
