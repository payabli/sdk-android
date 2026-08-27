package com.payabli.sdk.core.device.platform

import android.content.Context
import android.os.Build
import androidx.annotation.RestrictTo
import com.payabli.sdk.core.device.CardPresentLinkage
import com.payabli.sdk.core.telemetry.TelemetryDeviceContext
import com.payabli.sdk.core.telemetry.TelemetryDigest

/**
 * Reads what the platform says about this handset, once, for every event to carry.
 *
 * `Build` is named here and nowhere else outside the card-present description, which is what keeps it out of
 * the reach of a unit test: reading `Build.MODEL` on a JVM throws rather than answering, so every caller of
 * this has to have a device.
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

    /** The platform. Fixed here and different on the sibling SDK, which is what makes it worth sending. */
    internal const val OS: String = "Android"

    @Volatile
    private var cached: TelemetryDeviceContext? = null

    /**
     * The device facts, computed once.
     *
     * None of the inputs can change while the process lives, and the identifier's digest is worth avoiding
     * on a path that runs per session.
     */
    public fun of(context: Context): TelemetryDeviceContext =
        cached ?: synchronized(this) {
            cached ?: TelemetryDeviceContext(
                idHash = DeviceIdentifierFactory.of(context),
                type = if (CardPresentLinkage.isLinked()) TYPE else "",
                os = OS,
                osVersion = Build.VERSION.RELEASE.orEmpty(),
                modelName = Build.MODEL.orEmpty(),
                packageHash = TelemetryDigest.of(context.applicationContext.packageName.orEmpty()),
            ).also { cached = it }
        }
}
