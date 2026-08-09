package com.payabli.example.app.preflight.platform

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcManager
import android.os.Build
import com.payabli.example.app.preflight.DeviceFacts
import java.security.MessageDigest

/**
 * Reads what the device says about itself.
 *
 * The only file in the preflight package that touches a framework, which is what keeps
 * [com.payabli.example.app.preflight.TapToPayPreflight] testable on a host JVM. It sits under
 * `platform` for the same reason the SDK's own platform packages do, and is excluded from coverage
 * on the same grounds: no unit test can reach a line of it.
 */
object DeviceFactsReader {
    private const val PLAY_SERVICES = "com.google.android.gms"
    private const val PLAY_STORE = "com.android.vending"

    fun read(context: Context): DeviceFacts {
        val packageManager = context.packageManager
        return DeviceFacts(
            isEmulator = isEmulator(),
            model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            apiLevel = Build.VERSION.SDK_INT,
            hasNfcHardware = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC),
            isNfcEnabled = isNfcEnabled(context),
            playServicesInstalled = isInstalled(packageManager, PLAY_SERVICES),
            playStoreInstalled = isInstalled(packageManager, PLAY_STORE),
            packageName = context.packageName,
            signingCertificateDigest = signingCertificateDigest(context),
        )
    }

    /**
     * Several signals. No single property is reliable across emulator images, and every one of them
     * can be set by a custom ROM.
     */
    private fun isEmulator(): Boolean =
        Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu") ||
            Build.HARDWARE.contains("cutf_cvm") ||
            Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emu") ||
            Build.PRODUCT.contains("sdk_gphone") ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))

    /**
     * Through [NfcManager]. `NfcAdapter.getDefaultAdapter(Context)` is deprecated from API 34. A null
     * adapter means no NFC on this device.
     */
    private fun isNfcEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.NFC_SERVICE) as? NfcManager ?: return false
        return manager.defaultAdapter?.isEnabled == true
    }

    /**
     * Visibility for these two comes from the `<queries>` block in the manifest. Without it this
     * throws on API 30 and above and would report an installed package as absent.
     */
    private fun isInstalled(
        packageManager: PackageManager,
        packageName: String,
    ): Boolean =
        try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /**
     * SHA-256 of the first signing certificate, or null below API 28 where `signingInfo` does not
     * exist. Null means the check could not run, never that the signature is wrong.
     */
    @Suppress("DEPRECATION")
    private fun signingCertificateDigest(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val info =
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
            val signature = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
            MessageDigest
                .getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString(":") { "%02X".format(it) }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
