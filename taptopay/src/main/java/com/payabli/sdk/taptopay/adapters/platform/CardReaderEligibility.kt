package com.payabli.sdk.taptopay.adapters.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.payabli.sdk.taptopay.adapters.ReaderEligibility
import com.payabli.sdk.taptopay.provider.DeviceIneligibleException

/**
 * The platform floor a contactless payment is enabled above, which sits higher than the module's install
 * floor. Below it the feature is off, never degraded into taking a payment.
 */
internal const val CARD_PRESENT_MIN_API: Int = Build.VERSION_CODES.S

/** The permanent facts, read off this handset. Both fail closed. */
internal class CardReaderEligibility(
    context: Context,
) : ReaderEligibility {
    private val packageManager = context.applicationContext.packageManager

    override fun check() {
        if (Build.VERSION.SDK_INT < CARD_PRESENT_MIN_API) {
            throw DeviceIneligibleException(
                "contactless payments need API level $CARD_PRESENT_MIN_API or newer",
            )
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)) {
            throw DeviceIneligibleException("this device has no contactless radio")
        }
    }
}
