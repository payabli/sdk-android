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
 *
 * @property idHash which device this is, as a digest — the same value device registration sends, so one
 *   device is one identity in both, and the difference between one device retrying and a fleet failing once.
 * @property type what the SDK's host is, in the service's own vocabulary for a device record.
 * @property os which platform, in the service's own vocabulary. The only field that splits a mixed stream.
 * @property osVersion the platform release, as the platform states it: `14`, `15`, `16.1.2`.
 * @property modelName the handset, as the platform states it: `Pixel 7a`.
 * @property packageName which app this is. One entry point serves several, so this is what tells them apart.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class TelemetryDeviceContext(
    public val idHash: String,
    public val type: String,
    public val os: String,
    public val osVersion: String,
    public val modelName: String,
    public val packageName: String = "",
) {
    /**
     * Withholds [idHash] and [packageName], as `TelemetrySessionContext.toString` withholds the entry point:
     * this string reaches exception messages and crash reports, and those two name a device and an app.
     *
     * @return the type, platform, release and handset, withholding the digest and the app.
     */
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
