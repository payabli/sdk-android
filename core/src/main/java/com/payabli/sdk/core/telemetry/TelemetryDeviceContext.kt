package com.payabli.sdk.core.telemetry

import androidx.annotation.RestrictTo

/**
 * The device facts every event carries, fixed for the life of the process.
 *
 * Grouped rather than loose on [TelemetrySessionContext] because they answer one question — what is this
 * running on — and are read once at install. None of them is a caller's to supply: an emitting site names an
 * event and whatever properties that event declares, and everything here is added underneath it.
 *
 * **Every field is blank where there is no device to ask**, which is the SDK's own tests on a JVM. A blank
 * is omitted from the wire rather than sent empty, so a reader is never told a handset reported nothing for
 * its own model.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class TelemetryDeviceContext(
    /**
     * Which device this is, as a digest.
     *
     * The same value device registration sends, so one device is one identity in both. The session id says
     * which run an event came from; this says which device the run happened on, which is the difference
     * between one device retrying and a fleet failing once.
     */
    public val idHash: String,
    /** What the SDK's host is, in the service's own vocabulary for a device record. */
    public val type: String,
    /** Which platform, in the service's own vocabulary. The only field that splits a mixed stream. */
    public val os: String,
    /** The platform release, as the platform states it: `14`, `15`, `16.1.2`. */
    public val osVersion: String,
    /** The handset, as the platform states it: `Pixel 7a`. */
    public val modelName: String,
    /** Which app this is. One entry point serves several, so this is what tells them apart. */
    public val packageName: String = "",
) {
    /** Withholds nothing: not one of these identifies a merchant, a payer or an account. */
    override fun toString(): String =
        "TelemetryDeviceContext(type=$type, os=$os, osVersion=$osVersion, modelName=$modelName)"

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public companion object {
        /** What a run with no device reports: nothing, in every field. */
        public val NONE: TelemetryDeviceContext =
            TelemetryDeviceContext(
                idHash = "",
                type = "",
                os = "",
                osVersion = "",
                modelName = "",
                packageName = "",
            )
    }
}
