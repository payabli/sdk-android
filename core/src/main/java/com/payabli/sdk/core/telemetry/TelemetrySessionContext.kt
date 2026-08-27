package com.payabli.sdk.core.telemetry

import androidx.annotation.RestrictTo
import com.payabli.sdk.core.config.PayabliEnvironment

/**
 * What a reporting channel needs to know about the session it reports for.
 *
 * The session holds the configuration and does not publish it, so this is the narrow view a sibling artifact
 * gets: the values every event has to carry, and nothing else. No token, no provider, no transport.
 *
 * [sessionId] is minted once per installed session and identifies one SDK lifetime, so events from a single
 * run read as one sequence. It is a fresh value on every install rather than a device or install identifier:
 * it correlates a run and grants nothing, which is the same bar `LoggableFieldNames` holds `sid` to.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class TelemetrySessionContext(
    /** The partner integration point every event is attributed to. */
    public val entryPoint: String,
    /** Selects the base URL, and is reported alongside every event. */
    public val environment: PayabliEnvironment,
    /** The host's opt-out. False means no recorder is installed at all. */
    public val telemetryEnabled: Boolean,
    /** Identifies this SDK lifetime. Fresh per install. */
    public val sessionId: String,
    /**
     * What this is running on, for every event to carry.
     *
     * [sessionId] says which run an event came from; this says which device the run happened on, what kind
     * of device it is, and which platform release — the difference between one handset retrying and a fleet
     * failing once, and between a fault everywhere and a fault on one OS version.
     */
    public val device: TelemetryDeviceContext,
) {
    /**
     * [entryPoint] as a digest, which is what an event carries.
     *
     * The batch still carries the entry point itself, because that is what authorizes the request. This is
     * what everything reading event bodies groups by, so the raw value is absent from there. See
     * [TelemetryDigest] for the derivation, which the sibling platform has to match.
     */
    public val entryHash: String = TelemetryDigest.of(entryPoint)

    /**
     * Withholds the entry point, matching `PayabliConfig.toString`. It names a specific merchant, and this
     * string reaches exception messages and crash reports.
     */
    override fun toString(): String =
        "TelemetrySessionContext(environment=$environment, telemetryEnabled=$telemetryEnabled, " +
            "device=$device)"
}
