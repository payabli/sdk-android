package com.payabli.sdk.core.network.impl

import com.payabli.sdk.core.SessionWiring
import com.payabli.sdk.core.auth.testAuth
import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.network.PayabliTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.Duration

/**
 * Builds a transport holding [decorations] as its whole chain, so a test can prove `execute` applies whatever
 * chain it holds without a decoration having to exist in production.
 *
 * @param baseUrl the origin every request goes to.
 * @param decorations the chain to apply.
 * @param dispatcher where the blocking exchange runs.
 * @param logger where each request and response is logged.
 * @param callTimeout the budget for one whole call.
 * @param maxResponseBytes the ceiling on a response body.
 * @return the transport, holding the supplied chain.
 */
internal fun PayabliService.Companion.createWithDecorations(
    baseUrl: String,
    decorations: List<PayabliRequestDecoration>,
    dispatcher: CoroutineDispatcher,
    logger: SdkLogger = LoggerRegistry.of(LogCategory.NETWORK),
    callTimeout: Duration = DEFAULT_CALL_TIMEOUT,
    maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
): PayabliTransport =
    create(
        baseUrl,
        testAuth(),
        dispatcher,
        logger,
        callTimeout,
        maxResponseBytes,
        ClientFacts.NONE,
        SessionWiring(decorations = decorations),
    )
