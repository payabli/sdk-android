package com.payabli.sdk.taptopay.enrollment.platform

import android.content.Context
import android.os.Build
import com.payabli.sdk.core.device.platform.DeviceIdentifierFactory
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
 * the device registers.
 */
internal object DeviceDescriptionFactory {
    fun create(context: Context): DeviceDescription =
        DeviceDescription(
            hardwareId = DeviceIdentifierFactory.of(context),
            // Not sent: see DeviceDescription.deviceName.
            deviceName = null,
            model = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
        )
}
