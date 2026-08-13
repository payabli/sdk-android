package com.payabli.example.app.payment

import android.content.Context
import com.payabli.example.app.config.DemoConfiguration
import com.payabli.example.app.config.DemoEnvironment
import com.payabli.example.app.net.TokenServerClient
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where this app's SDK session comes from.
 *
 * Two steps, in this order, because neither can be skipped: the app's own backend mints a token, and the
 * session is configured with it. `PayabliConfig` refuses a blank one, so there is no session to hand a
 * capability until the token server has answered.
 *
 * **This is the piece an integrator copies.** The token is minted by their backend, reaches
 * [PayabliConfig.accessToken] and the provider, and goes nowhere else — not into screen state, not into
 * diagnostics, not into a log line.
 *
 * **One session for the process, and the SDK is what holds it.** It installs one and refuses a second
 * configuration with `INVALID_CONFIGURATION`, and the access token is part of the identity it compares, so a
 * freshly minted token is a different configuration. What this class keeps is that configuration, so the second
 * screen to ask gets the session the first one installed rather than a rejection.
 *
 * Keeping the token current is the provider's job, and it is called for every request that needs one.
 */
class PayInSessionSource(
    private val appContext: Context,
    /**
     * Read per call, because the launch override rewrites the token server's address after this is built and
     * one instance outlives that.
     */
    private val tokenClient: () -> TokenServerClient,
    private val configuration: DemoConfiguration,
    /**
     * Installing the session, substitutable for the reason [tokenClient] is.
     *
     * A test holds a startup open here and cancels inside it. The rule below — that a cancellation unwinds
     * rather than becoming a failed `Result` — cannot be observed any other way: `initialize` returns too
     * quickly to interrupt from outside, so a test cancelling during the token request would pass whether the
     * rule held or not.
     */
    private val startSession: suspend (PayabliConfig) -> Result<PayabliSession> = { config ->
        PayabliSession.initialize(config, HostBindings(appContext))
    },
) {
    private val lock = Mutex()

    /** The configuration a session was last started from, so the same one can be handed back. */
    private var started: PayabliConfig? = null

    /**
     * An initialized session, or the reason there is none.
     *
     * `initialize` is called every time rather than a session being held here. With the same configuration it
     * answers with the session it already installed and does no work; once that session has become
     * unrecoverable, the same call installs a fresh one, which is the recovery the SDK documents. A session
     * kept in a field instead was handed out after it had died, so the app could not submit again until the
     * process restarted, however healthy the backend had become.
     *
     * The failure is a `String` because it goes to a demo screen beside the step it belongs to. A real
     * integration reads `PayabliException.code` instead.
     */
    suspend fun session(): Result<PayabliSession> =
        lock.withLock {
            started?.let { config ->
                start(config).onSuccess { return@withLock Result.success(it) }
            }
            // No configuration yet, or the one held could not start a session: mint a token and build one.
            // A token minted after a rejection is a different configuration, which the SDK accepts because
            // the session it replaces is finished. `build` records the configuration itself.
            build().map { it.second }
        }

    private suspend fun build(): Result<Pair<PayabliConfig, PayabliSession>> {
        if (configuration.entryPoint.isBlank()) {
            return Result.failure(IllegalStateException("No entry point is configured, so nothing can be sent."))
        }
        val token = tokenClient().mintAccessToken() ?: return Result.failure(IllegalStateException(NO_TOKEN))

        val config =
            PayabliConfig(
                accessToken = token,
                entryPoint = configuration.entryPoint,
                environment = configuration.environment.sdkEnvironment,
                // Called again whenever a token is rejected, which is the whole point of minting per call.
                // Throwing is the honest answer when the server has nothing: the SDK treats a provider
                // failure as a terminal credential rejection, which is what a dead token server is.
                tokenProvider = {
                    tokenClient().mintAccessToken() ?: throw IllegalStateException(NO_TOKEN)
                },
            )

        // Recorded before the attempt rather than after it. `initialize` installs the session process-wide
        // before it returns, so anything that stops this coroutine in between — a screen closing, its
        // ViewModel clearing — would leave a session installed with nothing here remembering which
        // configuration it was. Every later attempt would then mint a fresh token, build a configuration that
        // is a different one by definition, and be refused as a second session for the life of the process.
        started = config

        // Not runCatching: that catches CancellationException as well, and turning cancellation into an
        // ordinary startup failure reports an error for a screen that simply went away.
        return try {
            start(config).map { session -> config to session }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private suspend fun start(config: PayabliConfig): Result<PayabliSession> = startSession(config)

    private companion object {
        const val NO_TOKEN = "The token server returned no access token."
    }
}

/** The SDK's name for the environment this demo is pointed at. */
private val DemoEnvironment.sdkEnvironment: PayabliEnvironment
    get() =
        when (this) {
            DemoEnvironment.QA -> PayabliEnvironment.QA
            DemoEnvironment.SANDBOX -> PayabliEnvironment.SANDBOX
            DemoEnvironment.PRODUCTION -> PayabliEnvironment.PRODUCTION
        }
