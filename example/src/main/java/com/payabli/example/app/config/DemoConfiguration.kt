package com.payabli.example.app.config

import com.payabli.example.app.BuildConfig

/** Which Payabli environment this build talks to. */
enum class DemoEnvironment(
    val label: String,
    val baseUrl: String,
) {
    QA("qa", "https://api-qa.payabli.com"),
    SANDBOX("sandbox", "https://api-sandbox.payabli.com"),
    PRODUCTION("production", "https://api.payabli.com"),
    ;

    val host: String get() = baseUrl.removePrefix("https://")
}

/**
 * Everything the SDK would be configured with, read once.
 *
 * There is no environment switcher and no editable field anywhere in this app, and that is the
 * design. A session captures its configuration when it is created, so a control that appeared to
 * change one of these after startup would be showing a value nothing ever received. The Setup screen
 * reads them back and offers no way to alter them.
 */
data class DemoConfiguration(
    val entryPoint: String,
    val appId: String,
    /** Blank when no expected certificate is configured, which means the check is not run. */
    val signingCertificate: String,
    val environment: DemoEnvironment,
    val diagnosticsEnabled: Boolean,
) {
    /** Non-null when the entry point cannot work, for [com.payabli.example.app.ui.components.DetailRow]. */
    val entryPointProblem: String?
        get() = if (entryPoint.isBlank()) "Not set. Configuration is keyed by entry point." else null

    companion object {
        /** The single point where generated build settings are read. */
        fun fromBuildConfig(): DemoConfiguration =
            DemoConfiguration(
                entryPoint = BuildConfig.DEMO_ENTRY_POINT,
                appId = BuildConfig.DEMO_APP_ID,
                signingCertificate = BuildConfig.DEMO_SIGNING_CERTIFICATE,
                // Sandbox: the environment an outside integrator can reach, and this app is the
                // thing they read first.
                environment = DemoEnvironment.SANDBOX,
                diagnosticsEnabled = BuildConfig.DEMO_DIAGNOSTICS,
            )
    }
}
