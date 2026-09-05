package com.payabli.example.app.sdk

import android.content.Context
import com.payabli.example.app.demo.config.DemoConfiguration
import com.payabli.example.app.demo.net.TokenServerClient
import com.payabli.sdk.core.HostBindings
import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.core.config.PayabliConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where this app's SDK session comes from.
 *
 * One step: the session is configured with a provider, and the SDK calls it when it first needs a token.
 * Nothing is minted here, so a session exists as soon as an entry point is configured and the token server
 * is not consulted until something sends a request.
 *
 * **This is the piece an integrator copies.** The token is minted by their backend inside the provider, is
 * held by the SDK, and goes nowhere else — not into screen state, not into diagnostics, not into a log line.
 * Nothing hands one over, so there is no copy of it here to leak.
 *
 * **One session for the process, and the SDK is what holds it.** It installs one and refuses a second
 * configuration with `INVALID_CONFIGURATION`. The configuration carries no credential, so nothing about a
 * token can make a second one look different: the same entry point and environment name the session already
 * installed. Nothing is kept here: asking again is what gets the right answer.
 *
 * Keeping the token current is the provider's job. It is called for the first token and again whenever one
 * is rejected, not for every request.
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
     * `initialize` is called every time and no session is held here: the SDK owns which session a call means,
     * so asking it is always current and caching it here never is. No token is minted by asking.
     *
     * The failure is a `String` because it goes to a demo screen beside the step it belongs to. A real
     * integration reads `PayabliException.code` instead.
     */
    suspend fun session(): Result<PayabliSession> = lock.withLock { build() }

    private suspend fun build(): Result<PayabliSession> {
        if (configuration.entryPoint.isBlank()) {
            return Result.failure(IllegalStateException("No entry point is configured, so nothing can be sent."))
        }
        // Not runCatching: that catches CancellationException as well, and turning cancellation into an
        // ordinary startup failure reports an error for a screen that simply went away.
        return try {
            start(config())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private fun config(): PayabliConfig =
        PayabliConfig(
            entryPoint = configuration.entryPoint,
            environment = configuration.environment.sdkEnvironment,
            // The only way a token reaches the SDK: called before the first request and again whenever one
            // is rejected. Throwing is the honest answer when the server has nothing, since the SDK reads a
            // provider failure as a credential failure, which is what a dead token server is.
            tokenProvider = {
                tokenClient().mintAccessToken() ?: throw IllegalStateException(NO_TOKEN)
            },
        )

    private suspend fun start(config: PayabliConfig): Result<PayabliSession> = startSession(config)

    private companion object {
        const val NO_TOKEN = "The token server returned no access token."
    }
}
