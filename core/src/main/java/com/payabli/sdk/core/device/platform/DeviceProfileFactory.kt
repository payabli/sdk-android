package com.payabli.sdk.core.device.platform

import android.content.Context
import android.os.Build
import androidx.annotation.RestrictTo
import com.payabli.sdk.core.device.CardPresentLinkage
import com.payabli.sdk.core.device.UsableValueCache
import com.payabli.sdk.core.telemetry.TelemetryDeviceContext

/**
 * Reads what the platform says about this handset, once. Telemetry, the client header and device registration
 * all take the model and OS version from here, so they report one handset.
 *
 * Reading `Build.MODEL` on a JVM throws rather than answering, so every caller of this has to have a device.
 *
 * **[TYPE] and [OS] are the service's own words for a device record**, not new ones invented here. Sending
 * anything else would put two vocabularies in one field, and the value a client sends could not be compared
 * with the value the service resolved for the same device.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object DeviceProfileFactory {
    /**
     * What the SDK's host is, where it is anything at all.
     *
     * `Softpos` is the member of the service's device vocabulary that names a phone-resident point of sale,
     * and it is the only one a handset can be: the other three are terminals. It is reported only by an app
     * that linked card-present, because only such an app can hold the device record this claim is comparable
     * against. See [CardPresentLinkage].
     */
    internal const val TYPE: String = "Softpos"

    /** The platform, fixed for this SDK. */
    internal const val OS: String = "Android"

    private val profile = UsableValueCache<TelemetryDeviceContext> { it.idHash.isNotBlank() }

    /** The device facts, held once the identifier in them has been read. */
    public fun of(context: Context): TelemetryDeviceContext =
        profile.get {
            TelemetryDeviceContext(
                idHash = DeviceIdentifierFactory.of(context),
                type = if (CardPresentLinkage.isLinked()) TYPE else "",
                os = OS,
                osVersion = Build.VERSION.RELEASE.orEmpty(),
                modelName = Build.MODEL.orEmpty(),
                packageName = context.applicationContext.packageName.orEmpty(),
            )
        }
}
