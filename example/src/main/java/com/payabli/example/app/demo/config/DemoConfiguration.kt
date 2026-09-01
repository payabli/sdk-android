package com.payabli.example.app.demo.config

import com.payabli.example.app.BuildConfig
import com.payabli.example.app.demo.ui.components.DetailRow
import com.payabli.example.app.sdk.DemoEnvironment

/**
 * The values the SDK is configured from, read once.
 *
 * A session captures its configuration when it is created, so nothing in this app edits one after
 * startup.
 */
data class DemoConfiguration(
    val entryPoint: String,
    val appId: String,
    /** Blank when no expected certificate is configured, which means the check is not run. */
    val signingCertificate: String,
    /**
     * The `payabli.demo.environment` label, as written. A label naming no [DemoEnvironment] still has
     * to be shown, which a resolved environment cannot carry.
     */
    val environmentSetting: String,
    val diagnosticsEnabled: Boolean,
    /**
     * Offers the button that fills the payment form with test values.
     *
     * `payabli.demo.prefill`, off by default, and the button is drawn in a debug build only. A release build
     * ignores the setting.
     */
    val prefillEnabled: Boolean = false,
) {
    /** Takes the environment directly, for a preview or a test that holds one. */
    constructor(
        entryPoint: String,
        appId: String,
        signingCertificate: String,
        environment: DemoEnvironment,
        diagnosticsEnabled: Boolean,
        prefillEnabled: Boolean = false,
    ) : this(entryPoint, appId, signingCertificate, environment.label, diagnosticsEnabled, prefillEnabled)

    /** [DemoEnvironment.DEFAULT] when the setting named none, which [environmentProblem] reports. */
    val environment: DemoEnvironment
        get() = DemoEnvironment.named(environmentSetting) ?: DemoEnvironment.DEFAULT

    /** Non-null when the entry point cannot work, for [DetailRow]. */
    val entryPointProblem: String?
        get() = if (entryPoint.isBlank()) "Not set. Configuration is keyed by entry point." else null

    /** Non-null when the setting named no environment, which means [environment] is the fallback. */
    val environmentProblem: String?
        get() =
            if (DemoEnvironment.named(environmentSetting) == null) {
                "payabli.demo.environment=$environmentSetting names no environment. " +
                    "Using ${DemoEnvironment.DEFAULT.label}. One of: ${DemoEnvironment.labels}."
            } else {
                null
            }

    companion object {
        /** The single point where generated build settings are read. */
        fun fromBuildConfig(): DemoConfiguration =
            DemoConfiguration(
                entryPoint = BuildConfig.DEMO_ENTRY_POINT,
                appId = BuildConfig.DEMO_APP_ID,
                signingCertificate = BuildConfig.DEMO_SIGNING_CERTIFICATE,
                environmentSetting = BuildConfig.DEMO_ENVIRONMENT,
                diagnosticsEnabled = BuildConfig.DEMO_DIAGNOSTICS,
                prefillEnabled = BuildConfig.DEMO_PREFILL,
            )
    }
}
