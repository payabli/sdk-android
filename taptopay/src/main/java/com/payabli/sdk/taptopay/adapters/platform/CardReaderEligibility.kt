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

/**
 * The permanent facts, read off this handset. Both fail closed.
 *
 * The two facts are parameters rather than reads inside [check], so a test can put this on the refusing
 * side of both. Every handset a test runs on qualifies, so without them the refusal is unreachable and the
 * assertion on what a refusal says never executes.
 */
internal class CardReaderEligibility(
    context: Context,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
    private val hasContactless: () -> Boolean = {
        context.applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
    },
) : ReaderEligibility {
    override fun check() {
        if (apiLevel < CARD_PRESENT_MIN_API) {
            throw DeviceIneligibleException(
                "contactless payments need API level $CARD_PRESENT_MIN_API or newer",
            )
        }
        if (!hasContactless()) {
            throw DeviceIneligibleException("this device has no contactless radio")
        }
    }
}
