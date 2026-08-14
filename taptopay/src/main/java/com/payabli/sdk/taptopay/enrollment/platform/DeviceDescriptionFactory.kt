package com.payabli.sdk.taptopay.enrollment.platform

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
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
     * local value with that lifetime — scoped to the signing key, the user and the device, and reset only by
     * a factory reset.
     *
     * The value is hashed before it is sent. The digest has the same lifetime, keeps the raw platform
     * identifier on the device, and cannot be joined against what any other library in the app reports from
     * the same source. The salt is versioned. Changing it re-registers every installed device.
     *
     * Truncated to half the digest. 128 bits is far past collision concerns for a per-paypoint lookup, and
     * the field lands in a column sized for a serial number.
     *
     * A device that returns nothing for the platform identifier is left to the caller as a blank, which
     * `/register` refuses by name. Substituting a random value here is what the sibling SDK does and is the
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
            hardwareId = if (installationId.isBlank()) "" else digest(installationId),
            // Not sent: see DeviceDescription.deviceName.
            deviceName = null,
            model = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
        )
    }

    private fun digest(installationId: String): String {
        val bytes =
            MessageDigest
                .getInstance("SHA-256")
                .digest("$installationId|$SALT".toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(IDENTIFIER_BYTES * 2)
        for (index in 0 until IDENTIFIER_BYTES) {
            hex.append(HEX[(bytes[index].toInt() shr 4) and 0xF])
            hex.append(HEX[bytes[index].toInt() and 0xF])
        }
        return hex.toString()
    }

    /** Versioned: changing it re-registers every device, so it moves only with that understood. */
    private const val SALT = "com.payabli.sdk.taptopay.hardware.v1"
    private const val IDENTIFIER_BYTES = 16
    private val HEX = "0123456789abcdef".toCharArray()
}
