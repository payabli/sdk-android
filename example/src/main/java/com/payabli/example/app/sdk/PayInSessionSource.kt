package com.payabli.example.app.sdk

import android.content.Context
import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.config.DemoEnvironment
import com.payabli.example.app.demo.net.TokenServerClient
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
 * configuration with `INVALID_CONFIGURATION`. A token is a credential rather than an identity and is not
 * compared, so a freshly minted one still names the session already installed for that entry point and
 * environment. Nothing is kept here: asking again is what gets the right answer.
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

    /**
     * An initialized session, or the reason there is none.
     *
     * A token is minted and `initialize` is called every time, and no session is held here: the SDK owns which
     * session a call means, so asking it is always current and caching it here never is.
     *
     * The failure is a `String` because it goes to a demo screen beside the step it belongs to. A real
     * integration reads `PayabliException.code` instead.
     */
    suspend fun session(): Result<PayabliSession> = lock.withLock { build() }

    private suspend fun build(): Result<PayabliSession> {
        if (configuration.entryPoint.isBlank()) {
            return Result.failure(IllegalStateException("No entry point is configured, so nothing can be sent."))
        }
        val token = tokenClient().mintAccessToken() ?: return Result.failure(IllegalStateException(NO_TOKEN))

        // Building the configuration is inside this too: it validates what the token server returned, and a
        // token carrying a newline is refused there rather than at the call below.
        //
        // Not runCatching: that catches CancellationException as well, and turning cancellation into an
        // ordinary startup failure reports an error for a screen that simply went away.
        return try {
            start(configFor(token))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private fun configFor(token: String): PayabliConfig =
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

    private suspend fun start(config: PayabliConfig): Result<PayabliSession> = startSession(config)

    private companion object {
        const val NO_TOKEN = "The token server returned no access token."
    }
}

/** The SDK's name for the environment this demo is pointed at. */
private val DemoEnvironment.sdkEnvironment: PayabliEnvironment
    get() =
        when (this) {
            DemoEnvironment.SANDBOX -> PayabliEnvironment.SANDBOX
            DemoEnvironment.PRODUCTION -> PayabliEnvironment.PRODUCTION
        }
