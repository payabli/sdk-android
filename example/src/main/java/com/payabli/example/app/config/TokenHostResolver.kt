package com.payabli.example.app.config

/**
 * Decides where the local token server is.
 *
 * Every input is a parameter: no `Context`, no `Build`, no `BuildConfig`. Reading those is
 * [com.payabli.example.app.preflight.platform.DeviceFactsReader]'s job, which keeps this rule
 * testable on a host JVM.
 */
object TokenHostResolver {
    const val DEFAULT_PORT: Int = 8787

    /** The name of the launch extra, so the resolver and whoever reads the Intent cannot disagree. */
    const val LAUNCH_EXTRA: String = "payabliTokenHost"

    // Both reviewed and marked. A literal address is worth a second look in general, and these two
    // are the subject rather than a shortcut: 10.0.2.2 is the emulator's documented alias for the
    // host machine's loopback interface and exists nowhere else, and 127.0.0.1 is loopback. Neither
    // is reachable from another host, neither is a real endpoint, and the debug network security
    // config permits cleartext to these two and to nothing else.
    private const val EMULATOR_LOOPBACK_ALIAS = "10.0.2.2" // NOSONAR: see above
    private const val DEVICE_LOOPBACK = "127.0.0.1" // NOSONAR: see above

    /**
     * In order: a launch extra, then a build setting, then the device kind.
     *
     * A physical device gets `127.0.0.1`, which works with `adb reverse tcp:8787 tcp:8787` and needs
     * no name resolution and no wide bind. A LAN address is what the first two rows are for.
     */
    fun resolve(
        launchOverride: String?,
        buildSettingHost: String,
        isEmulator: Boolean,
    ): TokenServerTarget {
        if (!launchOverride.isNullOrBlank()) {
            return TokenServerTarget(normalize(launchOverride), TokenHostSource.LaunchOverride)
        }
        if (buildSettingHost.isNotBlank()) {
            return TokenServerTarget(normalize(buildSettingHost), TokenHostSource.BuildSetting)
        }
        return if (isEmulator) {
            TokenServerTarget("http://$EMULATOR_LOOPBACK_ALIAS:$DEFAULT_PORT", TokenHostSource.Emulator)
        } else {
            TokenServerTarget("http://$DEVICE_LOOPBACK:$DEFAULT_PORT", TokenHostSource.Device)
        }
    }

    /**
     * Turns whatever someone had to hand into a base URL: a bare host, `host:port`, or a full URL.
     *
     * A value that already carries a scheme is taken as written, minus any path. That is the only way
     * to point the demo at an https endpoint. A value without one gets `http://` and the default port,
     * which is the local-server case.
     *
     * A port that is not a number falls back to the default. This is a developer override typed by
     * hand, not a parsed protocol, and failing to launch over a typo would be the worse outcome.
     */
    internal fun normalize(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return "http://$DEVICE_LOOPBACK:$DEFAULT_PORT"

        val schemeSeparator = trimmed.indexOf("://")
        if (schemeSeparator > 0) {
            val scheme = trimmed.substring(0, schemeSeparator)
            val rest = trimmed.substring(schemeSeparator + 3)
            val authority = rest.substringBefore('/')
            return "$scheme://$authority"
        }

        return "http://" + withPort(trimmed.substringBefore('/'))
    }

    /** Appends the default port unless the authority already carries one. */
    private fun withPort(authority: String): String {
        // An IPv6 literal is bracketed, and the colons inside the brackets are
        // part of the address, so the search for a port starts after the closing bracket.
        val portSearchFrom = if (authority.startsWith("[")) authority.indexOf(']') + 1 else 0
        if (portSearchFrom <= 0 && authority.startsWith("[")) return "$authority:$DEFAULT_PORT"

        val colon = authority.indexOf(':', startIndex = portSearchFrom)
        if (colon < 0) return "$authority:$DEFAULT_PORT"

        val host = authority.substring(0, colon)
        val port = authority.substring(colon + 1).toIntOrNull()
        return if (port == null) "$host:$DEFAULT_PORT" else "$host:$port"
    }
}
