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
     * [recovery] lets a capability widen what counts as a rejection on its own routes, or narrow it: the
     * card-present device routes pin the token captured at attestation, so a refresh there rotates it out of
     * the match.
     *
     * [providerTimeoutMillis] is the escape hatch for a slow broker. The default protects every reader,
     * because one provider holds them all while the refresh is de-duplicated, but the transport allows its own
     * calls longer than the host's callback, and that callback usually makes a network round trip too. An
     * integrator whose broker is legitimately slower widens it here.
     */
    internal fun authenticated(
        config: PayabliConfig,
        recovery: AuthRecoveryPolicy = AuthRecoveryPolicy(),
        logger: SdkLogger = LoggerRegistry.of(LogCategory.NETWORK),
        authLogger: SdkLogger = LoggerRegistry.of(LogCategory.AUTH),
        providerTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
        onAuthFailure: AuthFailureListener = AuthFailureListener { },
    ): PayabliTransport =
        authenticated(
            config.environment.baseUrl,
            config,
            recovery,
            logger,
            authLogger,
            providerTimeoutMillis,
            onAuthFailure,
        )

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
        recovery: AuthRecoveryPolicy = AuthRecoveryPolicy(),
        logger: SdkLogger = LoggerRegistry.of(LogCategory.NETWORK),
        authLogger: SdkLogger = LoggerRegistry.of(LogCategory.AUTH),
        providerTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
        onAuthFailure: AuthFailureListener = AuthFailureListener { },
    ): PayabliTransport =
        authenticated(baseUrl, config, recovery, logger, authLogger, providerTimeoutMillis, onAuthFailure)

    private fun authenticated(
        baseUrl: String,
        config: PayabliConfig,
        recovery: AuthRecoveryPolicy,
        logger: SdkLogger,
        authLogger: SdkLogger,
        providerTimeoutMillis: Long,
        onAuthFailure: AuthFailureListener,
    ): PayabliTransport {
        // One holder for both the chain that reads the token and the wrapper that refreshes it, which is what
        // makes a replay carry the token the refresh minted. Its own category, so a refresh is filterable as
        // auth rather than buried under network.
        val auth = PayabliAuth(config, authLogger, providerTimeoutMillis)
        return AuthenticatedTransport(
            base = PayabliService.create(baseUrl = baseUrl, auth = auth, logger = logger),
            auth = auth,
            recovery = recovery,
            logger = logger,
            onAuthFailure = onAuthFailure,
        )
    }
}
