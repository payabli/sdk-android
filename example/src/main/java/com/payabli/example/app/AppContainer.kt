package com.payabli.example.app

import android.content.Context
import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.config.TokenHostResolver
import com.payabli.example.app.config.TokenServerTarget
import com.payabli.example.app.diagnostics.DiagnosticsRegistry
import com.payabli.example.app.net.TokenServerClient
import com.payabli.example.app.payment.DemoPaymentFlowController
import com.payabli.example.app.payment.PaymentFlowController
import com.payabli.example.app.payment.PaymentOperation
import com.payabli.example.app.preflight.DeviceFacts
import com.payabli.example.app.preflight.platform.DeviceFactsReader
import com.payabli.example.app.terminal.DemoTerminalController
import com.payabli.example.app.terminal.TerminalController

/**
 * Everything the app is built from, assembled once per process.
 *
 * The SDK admits no dependency-injection framework. At seven objects a container is shorter than the
 * configuration a framework would need.
 *
 * Built once and never rebuilt, which is what makes the Setup screen honest. It reads these values
 * back and offers no way to change them: a session captures its configuration when it is created, so
 * a control here would appear to change something already decided.
 *
 * The two `Demo*` lines are the seam. When the SDK arrives, each becomes a real instance and nothing
 * else in the app moves.
 */
class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext

    val configuration: DemoConfiguration = DemoConfiguration.fromBuildConfig()

    /**
     * Read on every call, never cached.
     *
     * NFC is a Settings toggle and the readiness check is the thing that tells a reader to go and
     * flip it. Held as a snapshot, the "Recheck" button re-ran the same five checks against the
     * facts read at process start and gave the same answer, so the one action offered for the
     * problem appeared to do nothing.
     */
    val readDeviceFacts: () -> DeviceFacts = { DeviceFactsReader.read(appContext) }

    /** Cannot change while the process runs, and the token host is resolved once. */
    private val isEmulator: Boolean = readDeviceFacts().isEmulator

    val diagnostics: DiagnosticsRegistry = DiagnosticsRegistry()

    /** ⟵ swap point: the card-present SDK goes here. */
    val terminal: TerminalController = DemoTerminalController()

    /** ⟵ swap point: the payment SDK goes here. */
    val paymentMethodFlow: PaymentFlowController = DemoPaymentFlowController(PaymentOperation.StoreMethod)

    /** ⟵ swap point: the same, for a charge. */
    val captureFlow: PaymentFlowController = DemoPaymentFlowController(PaymentOperation.Capture)

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

    fun applyLaunchOverride(host: String?) {
        if (host.isNullOrBlank()) return
        tokenServer = resolveTokenServer(host)
    }

    private fun resolveTokenServer(launchOverride: String?): TokenServerTarget =
        TokenHostResolver.resolve(
            launchOverride = launchOverride,
            buildSettingHost = BuildConfig.DEMO_TOKEN_HOST,
            isEmulator = isEmulator,
        )
}
