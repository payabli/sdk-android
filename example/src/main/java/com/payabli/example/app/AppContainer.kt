package com.payabli.example.app

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.config.DemoEnvironment
import com.payabli.example.app.demo.config.TokenHostDefaults
import com.payabli.example.app.demo.config.TokenHostResolver
import com.payabli.example.app.demo.config.TokenServerTarget
import com.payabli.example.app.demo.diagnostics.DiagnosticsRegistry
import com.payabli.example.app.demo.net.TokenServerClient
import com.payabli.example.app.demo.preflight.DeviceFacts
import com.payabli.example.app.demo.preflight.platform.DeviceFactsReader
import com.payabli.example.app.demo.terminal.DemoTerminalController
import com.payabli.example.app.demo.terminal.TerminalController
import com.payabli.example.app.sdk.PayInSessionSource
import com.payabli.example.app.sdk.PayInStartup
import com.payabli.example.app.sdk.payInFlowGate
import com.payabli.example.app.sdk.payInStartup

/**
 * Everything the app is built from, assembled once per process.
 *
 * The SDK admits no dependency-injection framework, so this is the whole of the wiring.
 *
 * Built once and never rebuilt, which is what makes the Setup screen honest. It reads these values
 * back and offers no way to change them: a session captures its configuration when it is created, so
 * a control here would appear to change something already decided.
 *
 * What can be written is written at startup, before anything composes, and none of it is a control: the
 * token host from a launch Intent, and the entry point and environment from an instrumented test. Both
 * overrides exist because what they carry is not knowable at build time on the machine that needs it.
 *
 * The `Demo*` line is the card-present seam. The card-not-present screens start the SDK through
 * [payInStartup] and it submits for them.
 */
class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext

    /**
     * The build's settings, except in an instrumented test, which has no build settings to read.
     *
     * `private set` because [applyTestConfiguration] is the only thing that writes it, on the same terms
     * as [applyLaunchOverride]: once, before anything composes.
     */
    var configuration: DemoConfiguration = DemoConfiguration.fromBuildConfig()
        private set

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
     *
     * Built on first use rather than at construction, because it captures [configuration] and both writes
     * above land before any screen asks for a session. Built eagerly it would hold what the process started
     * with, and an override would reach the Setup screen but not the SDK.
     */
    private val sessionSource by lazy { PayInSessionSource(appContext, { tokenClient }, configuration) }

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

    /**
     * Gives an instrumented test the one setting it cannot have.
     *
     * The payment sequence gates its first step on a configured entry point, and an instrumented test runs
     * against whatever the build was given, which for a fresh checkout is nothing. Supplying it here keeps
     * that test hermetic: no credential, no properties file, and no build that behaves differently from the
     * one a developer runs.
     *
     * The environment is taken too, and not because the sequence needs it: `PayabliSession` refuses a
     * second `initialize` naming a different one, so a test that pins only the entry point still installs
     * whatever the build configured and collides with the next test on a build that set anything but the
     * default.
     *
     * Neither value names anything real: they stand in for build settings so the sequence can be driven,
     * and the entry point is one no paypoint carries.
     */
    @VisibleForTesting
    fun applyTestConfiguration(
        entryPoint: String,
        environment: DemoEnvironment,
    ) {
        configuration = configuration.copy(entryPoint = entryPoint, environmentSetting = environment.label)
    }

    private fun resolveTokenServer(launchOverride: String?): TokenServerTarget =
        TokenHostResolver.resolve(
            launchOverride = launchOverride,
            buildSettingHost = BuildConfig.DEMO_TOKEN_HOST,
            isEmulator = isEmulator,
            defaults = TokenHostDefaults.fromBuildConfig(),
        )
}
