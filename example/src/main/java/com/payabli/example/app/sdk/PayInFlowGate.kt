package com.payabli.example.app.sdk

import com.payabli.sdk.core.PayabliSession
import com.payabli.sdk.payin.PayabliPayIn
import kotlinx.coroutines.CoroutineScope

/**
 * The two steps between "the app started" and "the form can submit", in one call.
 *
 * Both payment screens need the same pair — a session, then a flow for this entry point — and neither should
 * carry the sequence. The screen's own step list is what tells a reader the order; this makes it one call.
 *
 * An interface because the real one needs a `Context` and a reachable token server, so a screen's own tests
 * substitute a gate that answers immediately.
 */
fun interface PayInFlowGate {
    /**
     * A flow, or the reason there is none.
     *
     * [scope] is the caller's, and the flow cancels with it. A ViewModel passes `viewModelScope`, so a
     * submission survives a rotation and stops when the screen is finished with.
     */
    suspend fun open(scope: CoroutineScope): Result<PayabliPayIn>
}

/** The real one: configure the session and build the flow for this entry point. Nothing is minted here. */
fun payInFlowGate(
    sessionSource: PayInSessionSource,
    entryPoint: String,
): PayInFlowGate =
    PayInFlowGate { scope ->
        sessionSource.session().map { session: PayabliSession ->
            PayabliPayIn(session, entryPoint, scope)
        }
    }
