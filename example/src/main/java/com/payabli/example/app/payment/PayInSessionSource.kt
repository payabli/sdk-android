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
 * **One session for the process, held here.** The SDK installs one and refuses a second configuration with
 * `INVALID_CONFIGURATION`, and the access token is part of the identity it compares, so a freshly minted token
 * is a different configuration. The second screen to ask reuses the session the first one installed.
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
) {
    private val lock = Mutex()

    private var installed: PayabliSession? = null

    /**
     * An initialized session, or the reason there is none.
     *
     * The failure is a `String` because it goes to a demo screen beside the step it belongs to. A real
     * integration reads `PayabliException.code` instead.
     */
    suspend fun session(): Result<PayabliSession> =
        lock.withLock {
            installed?.let { return@withLock Result.success(it) }
            build().onSuccess { installed = it }
        }

    private suspend fun build(): Result<PayabliSession> {
        if (configuration.entryPoint.isBlank()) {
            return Result.failure(IllegalStateException("No entry point is configured, so nothing can be sent."))
        }
        val token = tokenClient().mintAccessToken() ?: return Result.failure(IllegalStateException(NO_TOKEN))

        return runCatching {
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
        }.mapCatching { config ->
            PayabliSession.initialize(config, HostBindings(appContext)).getOrThrow()
        }
    }

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
