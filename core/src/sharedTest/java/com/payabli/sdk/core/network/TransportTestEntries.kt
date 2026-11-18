package com.payabli.sdk.core.network

import com.payabli.sdk.core.SessionWiring
import com.payabli.sdk.core.config.PayabliConfig
import com.payabli.sdk.core.network.impl.AuthenticatedTransport
import com.payabli.sdk.core.network.impl.ClientFacts
import kotlinx.coroutines.CoroutineDispatcher

/**
 * [TransportFactory.authenticated] against an explicit [baseUrl].
 *
 * @param baseUrl the origin every request goes to, in place of the environment's.
 * @param config the configuration the holder and the transport are built from.
 * @param dispatcher where the transport's blocking work runs.
 * @param assembly the rest, whose default is the shipped behaviour.
 * @param client what every request reports about this device. The default reports nothing.
 * @return the transport with the bearer and the recovery wrapper already around it.
 */
internal fun TransportFactory.authenticatedAgainst(
    baseUrl: String,
    config: PayabliConfig,
    dispatcher: CoroutineDispatcher,
    assembly: TransportAssembly = TransportAssembly(),
    client: ClientFacts = ClientFacts.NONE,
): AuthenticatedTransport = authenticated(config, dispatcher, client, assembly, SessionWiring(baseUrl = baseUrl))
