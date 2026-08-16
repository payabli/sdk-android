package com.payabli.sdk.taptopay.enrollment.platform

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.payabli.sdk.taptopay.BuildConfig
import com.payabli.sdk.taptopay.enrollment.DeviceDescription
import java.security.MessageDigest

/**
 * Reads what the platform will say about this handset.
 *
 * Separate from [DeviceDescription] because naming `Build` and `Settings.Secure` makes a file unreachable
 * from a unit test. Confining them here leaves the coordinator testable in full.
 */
internal object DeviceDescriptionFactory {
    /**
     * Derives the hardware identifier and reads the descriptive fields.
     *
     * **The identifier is a digest, not the platform value.** It has to survive an uninstall and reinstall,
     * which rules out anything the SDK stores for itself: the encrypted store lives in the no-backup
     * directory and goes with the app's data. The platform's per-app installation identifier is the only
     * local value with that lifetime. A factory reset resets it.
     *
     * The value is hashed before it is sent. The digest has the same lifetime and keeps the raw platform
     * identifier on the device, so a party holding only the digest cannot recover it.
     *
     * **It is a pseudonym, and it is not unlinkable.** Both of the other inputs are in the APK, so
     * any library that reads the same platform identifier inside this app can compute this value and match
     * it. What the digest buys is that the raw identifier is never sent, stored or logged. Making it
     * unlinkable would need a key or a server-issued value, and both break the lifetime above: a key in the
     * key store goes with the app's data, and there is no device handle to ask the service for one until
     * this identifier has already registered the device.
     *
     * **The host's package name is part of the input, and the identifier alone is not enough.** The platform
     * scopes that identifier more widely than one app, so two applications on one device can read the same
     * value; what this returns is what identifies the device, so two such applications on one paypoint would
     * register as one. Including the package makes the result per application whatever the platform's scoping
     * turns out to be, which is why it does not rest on that scoping.
     *
     * `BuildConfig.SDK_IDENTIFIER` is this module's namespace, set once in its build file. Changing it, or the
     * host's package, changes what this returns for every device.
     *
     * Truncated to half the digest. 128 bits is far past collision concerns for a per-paypoint lookup, and
     * the wire field is sized for a serial number.
     *
     * A device that returns nothing for the platform identifier is left to the caller as a blank, which
     * registration refuses. Substituting a random value here is what the sibling SDK does and is the
     * reason it registers a new device on every call down that path: an identifier invented per call is not
     * an identifier.
     *
     * `HardwareIds` is suppressed because reading the identifier is the requirement, and the mitigation the
     * check asks for is the digest above: the raw value is never held, sent or logged, which
     * `theHardwareIdentifierIsNotTheRawPlatformValue` asserts.
     */
    @SuppressLint("HardwareIds")
    fun create(context: Context): DeviceDescription {
        val installationId =
            Settings.Secure
                .getString(
                    context.applicationContext.contentResolver,
                    Settings.Secure.ANDROID_ID,
                ).orEmpty()

        return DeviceDescription(
            hardwareId =
                if (installationId.isBlank()) {
                    ""
                } else {
                    digest(installationId, context.applicationContext.packageName)
                },
            // Not sent: see DeviceDescription.deviceName.
            deviceName = null,
            model = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
        )
    }

    private fun digest(
        installationId: String,
        packageName: String,
    ): String {
        val bytes =
            MessageDigest
                .getInstance("SHA-256")
                .digest(
                    "$installationId|$packageName|${BuildConfig.SDK_IDENTIFIER}".toByteArray(Charsets.UTF_8),
                )
        return bytes.toHexString(0, IDENTIFIER_BYTES)
    }

    private const val IDENTIFIER_BYTES = 16
}
