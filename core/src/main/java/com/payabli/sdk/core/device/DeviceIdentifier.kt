package com.payabli.sdk.core.device

import java.security.MessageDigest

/**
 * Derives the identifier that says which device this is, from values a caller supplies.
 *
 * Platform-free on purpose: naming `Settings.Secure` here would put this out of reach of a unit test, and
 * this is the half worth testing. `DeviceIdentifierFactory` reads the platform value and calls this.
 *
 * **It is a digest, never the platform value.** The raw identifier stays on the device: what is sent, stored
 * and logged is this, and a party holding only this cannot recover it.
 *
 * **It is a pseudonym, and it is not unlinkable.** Two of the three inputs are in the APK, so any library
 * reading the same platform identifier inside this app can compute the same value and match it. What the
 * digest buys is that the raw identifier never leaves. Making it unlinkable would need a key or a
 * server-issued value, and both break the lifetime the identifier has to have: a key in the key store goes
 * with the app's data, and there is no device handle to ask a service for until this value has already
 * registered the device.
 *
 * **The host's package name is an input, and it has to be.** The platform scopes its identifier more widely
 * than one app, so two applications on one device can read the same value; this returns what identifies the
 * device, so without the package two such apps on one paypoint would register as one device. Including it
 * makes the result per application whatever the platform's scoping turns out to be, rather than resting on
 * that scoping.
 *
 * **Truncated to half the digest.** 128 bits is far past collision concerns for a per-paypoint lookup, and
 * the registration wire field is sized for a serial number.
 */
internal object DeviceIdentifier {
    /** Half a SHA-256, so the result is 32 lowercase hex characters. */
    internal const val IDENTIFIER_BYTES: Int = 16

    /**
     * The identifier, or a blank when the platform offered nothing.
     *
     * **A blank is returned rather than a substitute.** An identifier invented per call is not an identifier:
     * it registers a new device every time it is used and correlates nothing. Callers refuse a blank instead.
     */
    fun derive(
        installationId: String,
        hostPackageName: String,
        sdkIdentifier: String,
    ): String {
        if (installationId.isBlank()) return ""

        val bytes =
            MessageDigest
                .getInstance("SHA-256")
                .digest("$installationId|$hostPackageName|$sdkIdentifier".toByteArray(Charsets.UTF_8))

        return bytes.toHexString(0, IDENTIFIER_BYTES)
    }
}
