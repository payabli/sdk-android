package com.payabli.sdk.taptopay.enrollment.platform

import android.content.Context
import com.payabli.sdk.core.device.platform.DeviceIdentifierFactory
import com.payabli.sdk.core.device.platform.DeviceProfileFactory
import com.payabli.sdk.taptopay.enrollment.DeviceDescription

/**
 * Reads what the platform will say about this handset.
 *
 * Separate from [DeviceDescription] because naming `Build` makes a file unreachable from a unit test.
 * Confining it here leaves the coordinator testable in full.
 *
 * **The identifier is not derived here.** It comes from `:core`, which is what makes registration and
 * reporting name the same device: a second derivation would be a second identity the day either one moved.
 * A device that returns nothing for the platform identifier is left as a blank, and a blank is refused when
 * the device registers. The model and OS version come from `:core` too, so registration, the client header
 * and telemetry report one handset.
 */
internal object DeviceDescriptionFactory {
    fun create(context: Context): DeviceDescription {
        val profile = DeviceProfileFactory.of(context)
        return DeviceDescription(
            hardwareId = DeviceIdentifierFactory.of(context),
            // Not sent: see DeviceDescription.deviceName.
            deviceName = null,
            model = profile.modelName,
            osVersion = profile.osVersion,
        )
    }
}
