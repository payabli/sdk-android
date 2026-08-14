package com.payabli.example.app.demo.config
import com.payabli.example.app.demo.preflight.platform.DeviceFactsReader

/**
 * Decides where the local token server is.
 *
 * Every input is a parameter: no `Context`, no `Build`, no `BuildConfig`, and no address of its own.
 * Reading the device is [DeviceFactsReader]'s job and
 * reading the settings is [TokenHostDefaults]'s, which keeps this rule testable on a host JVM
 * against addresses no machine here has.
 */
object TokenHostResolver {
    /** The name of the launch extra, so the resolver and whoever reads the Intent cannot disagree. */
    const val LAUNCH_EXTRA: String = "payabliTokenHost"

    /**
     * In order: a launch extra, then a build setting, then the device kind.
     *
     * The last of those is [defaults], which arrives configured. A physical device gets its own
     * loopback, which works with `adb reverse` and needs no name resolution and no wide bind. A LAN
     * address is what the first two rows are for.
     */
    fun resolve(
        launchOverride: String?,
        buildSettingHost: String,
        isEmulator: Boolean,
        defaults: TokenHostDefaults,
    ): TokenServerTarget {
        if (!launchOverride.isNullOrBlank()) {
            return TokenServerTarget(normalize(launchOverride, defaults), TokenHostSource.LaunchOverride)
        }
        if (buildSettingHost.isNotBlank()) {
            return TokenServerTarget(normalize(buildSettingHost, defaults), TokenHostSource.BuildSetting)
        }
        return if (isEmulator) {
            TokenServerTarget("http://${defaults.emulatorHost}:${defaults.port}", TokenHostSource.Emulator)
        } else {
            TokenServerTarget("http://${defaults.deviceHost}:${defaults.port}", TokenHostSource.Device)
        }
    }

    /**
     * Turns whatever someone had to hand into a base URL: a bare host, `host:port`, or a full URL.
     *
     * A value that already carries a scheme is taken as written, minus any path. That is the only way
     * to point the demo at an https endpoint. A value without one gets `http://` and the configured
     * port, which is the local-server case.
     *
     * A port that is not a number falls back to the configured one, so a typo still launches.
     */
    internal fun normalize(
        raw: String,
        defaults: TokenHostDefaults,
    ): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return "http://${defaults.deviceHost}:${defaults.port}"

        val schemeSeparator = trimmed.indexOf("://")
        if (schemeSeparator > 0) {
            val scheme = trimmed.substring(0, schemeSeparator)
            val rest = trimmed.substring(schemeSeparator + 3)
            val authority = rest.substringBefore('/')
            return "$scheme://$authority"
        }

        return "http://" + withPort(trimmed.substringBefore('/'), defaults.port)
    }

    /** Appends the configured port unless the authority already carries one. */
    private fun withPort(
        authority: String,
        defaultPort: Int,
    ): String {
        // An IPv6 literal is bracketed, and the colons inside the brackets are
        // part of the address, so the search for a port starts after the closing bracket.
        val portSearchFrom = if (authority.startsWith("[")) authority.indexOf(']') + 1 else 0
        if (portSearchFrom <= 0 && authority.startsWith("[")) return "$authority:$defaultPort"

        val colon = authority.indexOf(':', startIndex = portSearchFrom)
        if (colon < 0) return "$authority:$defaultPort"

        val host = authority.substring(0, colon)
        val port = authority.substring(colon + 1).toIntOrNull()
        return if (port == null) "$host:$defaultPort" else "$host:$port"
    }
}
