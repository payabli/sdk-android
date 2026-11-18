package com.payabli.sdk.core

import android.content.Context
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.device.platform.DeviceIdentifierFactory
import com.payabli.sdk.core.network.PayabliTransport
import com.payabli.sdk.core.network.impl.AuthFailureListener
import com.payabli.sdk.core.telemetry.TelemetryBootstraps
import kotlinx.coroutines.sync.withLock

/**
 * Installs a session against an explicit [baseUrl] and with no host bindings by default.
 *
 * @param baseUrl the origin every request goes to.
 * @param config the configuration to install.
 * @param host the host bindings, or null for none.
 * @param identifierOf stands in for the platform identifier, so a test can give a real device none.
 * @return the installed session.
 */
internal suspend fun PayabliSession.Companion.initializeAgainst(
    baseUrl: String,
    config: PayabliConfig,
    host: HostBindings? = null,
    identifierOf: (Context) -> String = DeviceIdentifierFactory::of,
): PayabliSession =
    install(
        config,
        { host?.let { HostFacts.of(it, identifierOf) } ?: HostFacts.NONE },
        SessionWiring(baseUrl = baseUrl),
    )

/**
 * Installs a session whose transport [buildTransport] builds, so a test can hold the critical section open. A
 * failure the SDK did not classify converts here as in [PayabliSession.initialize], which makes a throwing
 * transport the evidence for that catch.
 *
 * @param config the configuration to install.
 * @param buildTransport builds the transport. The session renews no refused token.
 * @return the installed session.
 */
internal suspend fun PayabliSession.Companion.initializeWith(
    config: PayabliConfig,
    buildTransport: suspend (AuthFailureListener) -> PayabliTransport,
): PayabliSession = install(config, { HostFacts.NONE }, SessionWiring(transport = buildTransport))

/**
 * Drops the installed session and puts [PayabliSession.state] back, so one test cannot decide the outcome of
 * the next.
 *
 * The state is restored outside the lock and again under it; the session tests fail if either moves.
 */
internal suspend fun PayabliSession.Companion.reset() {
    sink.value = SdkState.Uninitialized

    lock.withLock {
        TelemetryBootstraps.stopInstalled()
        installed?.machine?.finish()
        installed = null
        sink.value = SdkState.Uninitialized
    }
}
