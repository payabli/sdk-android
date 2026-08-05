package com.payabli.sdk.core.network

import com.payabli.sdk.core.auth.DEFAULT_PROVIDER_TIMEOUT_MILLIS
import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.network.impl.AuthFailureListener
import com.payabli.sdk.core.network.impl.AuthenticatedTransport
import com.payabli.sdk.core.network.impl.PayabliService
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Assembles the auth stack for one [PayabliConfig]: a token holder, a transport, and the recovery wrapper
 * around them.
 *
 * `PayabliService` and `AuthenticatedTransport` stay `internal`: a caller receives something already correct
 * rather than the parts to assemble it, so no capability can assemble a request path with the bearer or the
 * recovery missing.
 *
 * **`internal`, because `PayabliSession` is what a capability asks now.** This could only *ask* each caller
 * to build one per config and share it, and two calls meant two refresh domains. One session serves every
 * capability, never two; a doc comment cannot enforce that and a session owning one of these can.
 */
internal object TransportFactory {
    /**
     * Builds one auth domain. Called once per session; see the class note for why nothing outside `:core`
     * calls it directly any more.
     *
     * [dispatcher] has no default. `PayabliSession` is the layer an integrating app calls, so it is the one
     * place that picks a dispatcher; every layer under it is handed one. A default here would mean a caller
     * that narrowed parallelism, or a test that substituted a dispatcher, silently did not get it, and nothing
     * would report that.
     *
     * [assembly] is the rest, and its default is the shipped behaviour. Nothing outside `:core` can set it, so
     * do not describe it as configuration.
     */
    internal fun authenticated(
        config: PayabliConfig,
        dispatcher: CoroutineDispatcher,
        assembly: TransportAssembly = TransportAssembly(),
    ): PayabliTransport = authenticated(config.environment.baseUrl, config, dispatcher, assembly)

    /**
     * Same, against an explicit [baseUrl], for `:core`'s own tests.
     *
     * Kept narrow rather than promoted now that this object is `internal`. `@VisibleForTesting` is a Lint hint
     * and leaves a member public in bytecode, so as published API this would be an origin override: a caller
     * could send [PayabliConfig.accessToken] to any origin it liked, which is exactly what [PayabliEnvironment]
     * promises shipped configuration cannot do, "not even behind a debug flag". A capability that needs a
     * live-server test wants a fixtures artifact, not a hole here.
     */
    internal fun authenticatedAgainst(
        baseUrl: String,
        config: PayabliConfig,
        dispatcher: CoroutineDispatcher,
        assembly: TransportAssembly = TransportAssembly(),
    ): PayabliTransport = authenticated(baseUrl, config, dispatcher, assembly)

    private fun authenticated(
        baseUrl: String,
        config: PayabliConfig,
        dispatcher: CoroutineDispatcher,
        assembly: TransportAssembly,
    ): PayabliTransport {
        // One holder for both the chain that reads the token and the wrapper that refreshes it, which is what
        // makes a replay carry the token the refresh minted. Its own category, so a refresh is filterable as
        // auth rather than buried under network.
        val auth = PayabliAuth(config, assembly.authLogger, assembly.providerTimeoutMillis)
        return AuthenticatedTransport(
            base =
                PayabliService.create(
                    baseUrl = baseUrl,
                    auth = auth,
                    dispatcher = dispatcher,
                    logger = assembly.logger,
                ),
            auth = auth,
            recovery = assembly.recovery,
            logger = assembly.logger,
            onAuthFailure = assembly.onAuthFailure,
        )
    }
}

/**
 * The parts of the auth stack that are not where the request goes: the loggers, the policies, the listener.
 *
 * One parameter because they travel together and none of them is a decision a caller makes. Each default is
 * the shipped behaviour, and `:core`'s own tests vary a piece by naming it. Nothing outside `:core` can
 * construct one, so do not describe these as configuration.
 *
 * Grouped when adding [TransportFactory]'s dispatcher pushed the factory functions to eight parameters. A
 * list that long is read positionally at a glance, and four of these are the same two types, where a
 * transposed pair compiles and produces a transport that logs to the wrong category or refuses the wrong
 * failures.
 *
 * [providerTimeoutMillis] bounds a provider that never returns, so it cannot wedge every reader waiting on
 * the same refresh.
 */
internal class TransportAssembly(
    val recovery: AuthRecoveryPolicy = AuthRecoveryPolicy(),
    val logger: SdkLogger = LoggerRegistry.of(LogCategory.NETWORK),
    val authLogger: SdkLogger = LoggerRegistry.of(LogCategory.AUTH),
    val providerTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
    val onAuthFailure: AuthFailureListener = AuthFailureListener { },
)
