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
 * The only ABI the card reader ships native code for.
 *
 * The vendor's kernel carries one library and no 32-bit build, so a handset without this ABI cannot load
 * the reader whatever else it offers. Nothing in a manifest or a Gradle setting expresses that.
 */
internal const val CARD_PRESENT_REQUIRED_ABI: String = "arm64-v8a"

/**
 * Build values a handset does not carry and an emulator image does.
 *
 * **Each one names an emulator rather than an absence.** `unknown` is what Android itself substitutes for a
 * build property it cannot read, so a handset missing one property would match it and be reported as
 * unable to take payments. A marker that fires on a real device is worse than one that misses an image,
 * because `false` is the half of this answer a host is told to rely on.
 *
 * These say what the device looks like. [CardReaderEligibility] says what it can do, and a build matching
 * none of these still cannot read a card without a radio. A match is reported as unsupported and refuses
 * nothing.
 */
private val EMULATOR_MARKERS =
    listOf(
        "generic",
        "google_sdk",
        "sdk_gphone",
        "sdk_phone",
        "emu64",
        "emulator",
        "android sdk built for",
        "genymotion",
        "ranchu",
        "goldfish",
        "cuttlefish",
    )

/** Whether this build looks like an emulator image rather than a handset. */
internal fun looksEmulated(
    brand: String = Build.BRAND,
    device: String = Build.DEVICE,
    fingerprint: String = Build.FINGERPRINT,
    manufacturer: String = Build.MANUFACTURER,
    model: String = Build.MODEL,
    product: String = Build.PRODUCT,
    hardware: String = Build.HARDWARE,
): Boolean =
    listOf(brand, device, fingerprint, manufacturer, model, product, hardware)
        .any { value -> EMULATOR_MARKERS.any { it in value.lowercase() } }

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
    private val abis: () -> List<String> = { Build.SUPPORTED_ABIS.orEmpty().toList() },
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
        if (CARD_PRESENT_REQUIRED_ABI !in abis()) {
            throw DeviceIneligibleException(
                "the card reader runs only on $CARD_PRESENT_REQUIRED_ABI, which this device does not offer",
            )
        }
    }

    /**
     * The same facts as [check], answered rather than raised.
     *
     * Only a refusal becomes `false`. Anything else leaves this the way it arrived: `false` is the answer
     * a caller is told to rely on, and a linkage error or a defect turning into one would say a device
     * cannot take payments when nothing has established that.
     */
    fun isSatisfied(): Boolean =
        try {
            check()
            true
        } catch (ineligible: DeviceIneligibleException) {
            false
        }
}
