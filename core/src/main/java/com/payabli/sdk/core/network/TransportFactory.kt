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
import com.payabli.sdk.core.network.impl.ClientFacts
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
     * @param config the configuration the holder and the transport are built from.
     * @param dispatcher where the transport's blocking work runs, no default: `PayabliSession` is the one place that picks one.
     * @param client what every request reports about this device; no default, since only the session can read it.
     * @param assembly the rest, whose default is the shipped behaviour. Nothing outside `:core` can set it.
     * @return the transport with the bearer and the recovery wrapper already around it.
     */
    internal fun authenticated(
        config: PayabliConfig,
        dispatcher: CoroutineDispatcher,
        client: ClientFacts,
        assembly: TransportAssembly = TransportAssembly(),
    ): AuthenticatedTransport = authenticated(config.environment.baseUrl, config, dispatcher, client, assembly)

    /**
     * Same, against an explicit [baseUrl], for `:core`'s own tests. Kept narrow: `@VisibleForTesting`
     * leaves a member public in bytecode, so this would be an origin override — a caller able to send
     * the session's token to any origin, which [PayabliEnvironment] promises shipped configuration cannot do.
     *
     * @param baseUrl the origin every request goes to, overriding the environment's.
     * @param config the configuration the holder and the transport are built from.
     * @param dispatcher where the transport's blocking work runs; no default, for the reason [authenticated] gives.
     * @param assembly the rest, whose default is the shipped behaviour.
     * @param client what every request reports about this device. The default reports nothing, the SDK's own tests on a JVM.
     * @return the transport with the bearer and the recovery wrapper already around it.
     */
    internal fun authenticatedAgainst(
        baseUrl: String,
        config: PayabliConfig,
        dispatcher: CoroutineDispatcher,
        assembly: TransportAssembly = TransportAssembly(),
        client: ClientFacts = ClientFacts.NONE,
    ): AuthenticatedTransport = authenticated(baseUrl, config, dispatcher, client, assembly)

    private fun authenticated(
        baseUrl: String,
        config: PayabliConfig,
        dispatcher: CoroutineDispatcher,
        client: ClientFacts,
        assembly: TransportAssembly,
    ): AuthenticatedTransport {
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
                    client = client,
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
