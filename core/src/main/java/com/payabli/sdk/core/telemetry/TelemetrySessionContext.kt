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
 *
 * @property environment selects the base URL, and is reported alongside every event.
 * @property sessionId identifies this SDK lifetime. Fresh per install.
 * @property device what this is running on, for every event to carry: which device the run happened on,
 *   what kind of device it is, and which platform release — the difference between one handset retrying
 *   and a fleet failing once, and between a fault everywhere and a fault on one OS version.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class TelemetrySessionContext(
    entryPoint: String,
    public val environment: PayabliEnvironment,
    public val sessionId: String,
    public val device: TelemetryDeviceContext,
) {
    /**
     * The partner integration point every event is attributed to.
     *
     * Trimmed, because the request writers send it trimmed. Untrimmed, a padded configuration reported under
     * one spelling what the service recorded under another, and the check that keeps a record off a channel
     * it does not belong to compared the two exactly, so a flow using the trimmed form dropped every event.
     */
    public val entryPoint: String = entryPoint.trim()

    /**
     * This session, attributed to [entryPoint] instead of its own.
     *
     * A capability can be pointed at an entry point other than the one the session was configured with, and
     * the report has to name the one the request was sent to. Everything else is the session's: the run is
     * the same run and the device is the same device.
     *
     * @param entryPoint the integration point the report names, trimmed as the request writers send it.
     * @return this session attributed to [entryPoint], or this same object when it already matches.
     */
    public fun forEntryPoint(entryPoint: String): TelemetrySessionContext =
        if (entryPoint.trim() == this.entryPoint) {
            this
        } else {
            TelemetrySessionContext(entryPoint, environment, sessionId, device)
        }

    /**
     * Withholds the entry point, matching `PayabliConfig.toString`. It names a specific merchant, and this
     * string reaches exception messages and crash reports.
     *
     * @return the environment and the device, withholding the entry point.
     */
    override fun toString(): String = "TelemetrySessionContext(environment=$environment, device=$device)"
}
