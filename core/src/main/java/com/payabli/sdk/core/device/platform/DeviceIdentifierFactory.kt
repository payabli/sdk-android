package com.payabli.sdk.core.device.platform

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.annotation.RestrictTo
import com.payabli.sdk.core.BuildConfig
import com.payabli.sdk.core.device.DeviceIdentifier

/**
 * Reads the platform's per-app installation identifier and returns the digest of it.
 *
 * **One identifier for the whole SDK.** Device registration and telemetry both take this value, so a device
 * is the same device in both, and neither invents an identity the other cannot recognise. It lives in `:core`
 * for that reason: it was card-present's until 2026-08-25, which put it out of reach of everything else.
 *
 * **The platform identifier is the only local value with the lifetime this needs.** It has to survive an
 * uninstall and reinstall, which rules out anything the SDK stores for itself — the encrypted store lives in
 * the no-backup directory and goes with the app's data. A factory reset resets it.
 *
 * `Settings.Secure` and `Context` are named here and nowhere else, which is what keeps
 * [DeviceIdentifier] reachable from a unit test.
 *
 * `HardwareIds` is suppressed because reading the identifier is the requirement, and the mitigation the check
 * asks for is the digest: the raw value is never held, sent or logged.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object DeviceIdentifierFactory {
    @Volatile
    private var cached: String? = null

    /**
     * The identifier for this app on this device, or a blank when the platform offered nothing.
     *
     * Computed once. The inputs cannot change while the process lives, and both a `Settings.Secure` read and
     * a digest are worth avoiding on a path that runs per session and per registration.
     */
    @SuppressLint("HardwareIds")
    public fun of(context: Context): String =
        cached ?: synchronized(this) {
            cached ?: derive(context).also { cached = it }
        }

    private fun derive(context: Context): String {
        val application = context.applicationContext
        val installationId =
            Settings.Secure
                .getString(application.contentResolver, Settings.Secure.ANDROID_ID)
                .orEmpty()

        return DeviceIdentifier.derive(
            installationId = installationId,
            hostPackageName = application.packageName,
            sdkIdentifier = BuildConfig.SDK_IDENTIFIER,
        )
    }
}
