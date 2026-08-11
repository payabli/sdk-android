package com.payabli.example.app.config

/** Which rule decided where the token server is. */
enum class TokenHostSource {
    /** A `payabliTokenHost` string extra on the launch Intent. */
    LaunchOverride,

    /** `payabli.demo.tokenHost` in secrets.properties or on the Gradle command line. */
    BuildSetting,

    /** Nothing was configured, and this build is running on an emulator. */
    Emulator,

    /** Nothing was configured, and this build is running on a physical device. */
    Device,
}

/**
 * Where the local token server is, and why the app thinks so.
 *
 * The reason travels with the address because "cannot reach the token server" has four different
 * fixes depending on which rule chose it, and the Setup screen showing [explanation] is what turns a
 * failed probe into a next step.
 */
data class TokenServerTarget(
    val baseUrl: String,
    val source: TokenHostSource,
) {
    /**
     * The route that mints a fresh token.
     *
     * `exchange-token`. The `access-token` route serves a cached value, and the SDK's token provider
     * is contractually required to mint on every call. Probing the route the SDK will call is the only
     * probe worth trusting.
     */
    val accessTokenUrl: String get() = "$baseUrl/payabli/exchange-token"

    val healthUrl: String get() = "$baseUrl/health"

    /** `host:port` as chosen, so anything quoting an address quotes the one in use. */
    private val authority: String get() = baseUrl.substringAfter("://")

    /** Blank for an override that carried a scheme and no port; the default rows always have one. */
    private val port: String get() = authority.substringAfterLast(':', "")

    /**
     * Why this address, in the words of the fix.
     *
     * The two default rows name the address and, for a device, the command that makes it work, both
     * read from [baseUrl]. The hosts and the port are settings, so any address here is one build's.
     */
    val explanation: String
        get() =
            when (source) {
                TokenHostSource.LaunchOverride -> "overridden by the payabliTokenHost launch extra"
                TokenHostSource.BuildSetting -> "set by payabli.demo.tokenHost"
                TokenHostSource.Emulator -> "emulator, $authority reaches this machine's loopback"
                TokenHostSource.Device ->
                    if (port.isBlank()) {
                        "device, $authority reached over adb reverse"
                    } else {
                        "device, $authority via adb reverse tcp:$port tcp:$port"
                    }
            }
}
