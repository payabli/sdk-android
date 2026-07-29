package com.payabli.sdk.core.network

import androidx.annotation.RestrictTo
import com.payabli.sdk.core.auth.DEFAULT_PROVIDER_TIMEOUT_MILLIS
import com.payabli.sdk.core.auth.PayabliAuth
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.PayabliLogger
import com.payabli.sdk.core.logging.PayabliLoggers
import com.payabli.sdk.core.network.impl.AuthenticatedTransport
import com.payabli.sdk.core.network.impl.PayabliService

/**
 * The only way to obtain a transport from outside `:core`, so a capability shipped as its own artifact does
 * not reimplement the network and auth layers.
 *
 * `PayabliService` and `AuthenticatedTransport` stay `internal`: a caller receives something already correct
 * rather than the parts to assemble it, so no capability can assemble a request path with the bearer or the
 * recovery missing.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object PayabliTransports {
    /**
     * **Create one per [config] and share it.** Each call builds its own token holder, and two holders are two
     * refresh domains, which loses the de-duplication [PayabliAuth] exists to provide.
     *
     * [recovery] lets a capability widen what counts as a rejection on its own routes.
     *
     * [providerTimeoutMillis] is the escape hatch for a slow broker. The default protects every reader,
     * because one provider holds them all while the refresh is de-duplicated, but the transport allows its own
     * calls longer than the host's callback, and that callback usually makes a network round trip too. An
     * integrator whose broker is legitimately slower widens it here.
     */
    public fun authenticated(
        config: PayabliConfig,
        recovery: AuthRecoveryPolicy = AuthRecoveryPolicy(),
        logger: PayabliLogger = PayabliLoggers.of(LogCategory.NETWORK),
        authLogger: PayabliLogger = PayabliLoggers.of(LogCategory.AUTH),
        providerTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
    ): PayabliTransport =
        authenticated(
            config.environment.baseUrl,
            config,
            recovery,
            logger,
            authLogger,
            providerTimeoutMillis,
        )

    /**
     * Same, against an explicit [baseUrl], for `:core`'s own tests.
     *
     * `internal` rather than annotated. `@VisibleForTesting` is a Lint hint and leaves the member public in
     * bytecode, so as published API this was an origin override: a caller could send [PayabliConfig.accessToken]
     * to any origin it liked, which is exactly what [PayabliEnvironment] promises shipped configuration cannot
     * do, "not even behind a debug flag". A capability that needs a live-server test wants a fixtures artifact,
     * not a hole here.
     */
    internal fun authenticatedAgainst(
        baseUrl: String,
        config: PayabliConfig,
        recovery: AuthRecoveryPolicy = AuthRecoveryPolicy(),
        logger: PayabliLogger = PayabliLoggers.of(LogCategory.NETWORK),
        authLogger: PayabliLogger = PayabliLoggers.of(LogCategory.AUTH),
        providerTimeoutMillis: Long = DEFAULT_PROVIDER_TIMEOUT_MILLIS,
    ): PayabliTransport = authenticated(baseUrl, config, recovery, logger, authLogger, providerTimeoutMillis)

    private fun authenticated(
        baseUrl: String,
        config: PayabliConfig,
        recovery: AuthRecoveryPolicy,
        logger: PayabliLogger,
        authLogger: PayabliLogger,
        providerTimeoutMillis: Long,
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
        )
    }
}
