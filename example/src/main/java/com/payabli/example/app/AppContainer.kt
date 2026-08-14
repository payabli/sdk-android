package com.payabli.example.app

import android.content.Context
import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.config.TokenHostDefaults
import com.payabli.example.app.config.TokenHostResolver
import com.payabli.example.app.config.TokenServerTarget
import com.payabli.example.app.diagnostics.DiagnosticsRegistry
import com.payabli.example.app.net.TokenServerClient
import com.payabli.example.app.payment.PayInSessionSource
import com.payabli.example.app.payment.PayInStartup
import com.payabli.example.app.payment.payInFlowGate
import com.payabli.example.app.payment.payInStartup
import com.payabli.example.app.preflight.DeviceFacts
import com.payabli.example.app.preflight.platform.DeviceFactsReader
import com.payabli.example.app.terminal.DemoTerminalController
import com.payabli.example.app.terminal.TerminalController

/**
 * Everything the app is built from, assembled once per process.
 *
 * The SDK admits no dependency-injection framework, so this is the whole of the wiring.
 *
 * Built once and never rebuilt, which is what makes the Setup screen honest. It reads these values
 * back and offers no way to change them: a session captures its configuration when it is created, so
 * a control here would appear to change something already decided.
 *
 * The `Demo*` line is the card-present seam. The card-not-present screens start the SDK through
 * [payInStartup] and it submits for them.
 */
class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext

    val configuration: DemoConfiguration = DemoConfiguration.fromBuildConfig()

    /**
     * Read on every call, never cached.
     *
     * NFC is a Settings toggle and the readiness check is what tells a reader to go and flip it, so
     * "Recheck" reads the device again and can report the change.
     */
    val readDeviceFacts: () -> DeviceFacts = { DeviceFactsReader.read(appContext) }

    /** Cannot change while the process runs, and the token host is resolved once. */
    private val isEmulator: Boolean = readDeviceFacts().isEmulator

    val diagnostics: DiagnosticsRegistry = DiagnosticsRegistry()

    /** ⟵ swap point: the card-present SDK goes here. */
    val terminal: TerminalController = DemoTerminalController()

    /**
     * Where the token server is.
     *
     * `var` for one reason: the launch override arrives on the Intent, which the Activity sees after
     * the Application has been created. [applyLaunchOverride] is the only thing that writes it, and
     * it is called once before any screen composes.
     */
    var tokenServer: TokenServerTarget = resolveTokenServer(launchOverride = null)
        private set

    val tokenClient: TokenServerClient get() = TokenServerClient(tokenServer)

    /**
     * The SDK session, held here because there is one per process and the second screen to ask has to reach
     * the one the first installed. It reads [tokenClient] per call, so a launch override still applies.
     */
    private val sessionSource = PayInSessionSource(appContext, { tokenClient }, configuration)

    /**
     * Step one for both payment screens: the token server, then the SDK.
     *
     * Read per call, and declared after [tokenServer]: built at construction it would capture a token
     * server the launch override has not rewritten yet.
     */
    val payInStartup: PayInStartup
        get() =
            payInStartup(
                tokenClient = tokenClient,
                gate = payInFlowGate(sessionSource = sessionSource, entryPoint = configuration.entryPoint),
            )

    fun applyLaunchOverride(host: String?) {
        if (host.isNullOrBlank()) return
        tokenServer = resolveTokenServer(host)
    }

    private fun resolveTokenServer(launchOverride: String?): TokenServerTarget =
        TokenHostResolver.resolve(
            launchOverride = launchOverride,
            buildSettingHost = BuildConfig.DEMO_TOKEN_HOST,
            isEmulator = isEmulator,
            defaults = TokenHostDefaults.fromBuildConfig(),
        )
}
