package com.payabli.example.app.config

import com.payabli.example.app.BuildConfig

/**
 * Where the local token server is when nothing overrides it.
 *
 * These are deployment values, not constants: an emulator that maps the host machine differently, or
 * a device forwarded on another port, is a settings change. They arrive from `payabli.demo.*` in
 * `example/secrets.properties` through `BuildConfig`, which is where every other configured value in
 * this app comes from, and reach [TokenHostResolver] as a parameter so that file keeps reading
 * nothing for itself.
 *
 * @param emulatorHost the emulator's alias for the host machine's loopback interface.
 * @param deviceHost the device's own loopback, which `adb reverse` forwards to the development
 *   machine.
 */
data class TokenHostDefaults(
    val emulatorHost: String,
    val deviceHost: String,
    val port: Int,
) {
    companion object {
        fun fromBuildConfig(): TokenHostDefaults =
            TokenHostDefaults(
                emulatorHost = BuildConfig.DEMO_EMULATOR_TOKEN_HOST,
                deviceHost = BuildConfig.DEMO_DEVICE_TOKEN_HOST,
                port = BuildConfig.DEMO_TOKEN_PORT,
            )
    }
}
