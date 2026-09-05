package com.payabli.example.app.sdk

import com.payabli.example.app.demo.net.TokenServerClient
import com.payabli.example.app.demo.net.checkToken
import kotlinx.coroutines.CoroutineScope

/**
 * Step one of both payment screens: check the token server is reachable, then start the SDK.
 *
 * Two calls rather than a handover. Nothing the probe returns is passed on: the SDK is configured with a
 * provider and mints through it when it first needs a token. The probe runs first so a dead token server is
 * reported at startup instead of at the first payment.
 *
 * One type, so both view models call the sequence and neither carries it. An interface for the reason
 * [PayInFlowGate] is one: the real thing needs a reachable server and a `Context`, and a screen's own tests
 * answer immediately.
 */
fun interface PayInStartup {
    /**
     * What the screen shows for the step, and what it submits through afterwards.
     *
     * [scope] is the caller's and becomes the flow's, so a ViewModel passes `viewModelScope`.
     */
    suspend fun start(scope: CoroutineScope): Started

    /** @param isReady both halves worked, so the form can be shown. */
    data class Started(
        val text: String,
        val isReady: Boolean,
        val payments: PayInFlowHandle?,
    )
}

/** The real one: probe the token server, then open the gate. The probe's token is not passed on. */
fun payInStartup(
    tokenClient: TokenServerClient,
    gate: PayInFlowGate,
): PayInStartup =
    PayInStartup { scope ->
        val probe = tokenClient.checkToken()
        if (!probe.reachable) {
            PayInStartup.Started(text = probe.text, isReady = false, payments = null)
        } else {
            gate.open(scope).fold(
                onSuccess = { flow ->
                    PayInStartup.Started(probe.text, isReady = true, payments = SdkPayInFlowHandle(flow))
                },
                // The token server answered and the SDK still did not start, so the line says which half
                // failed. A screen showing only the probe's verdict would offer a form that cannot submit.
                onFailure = { cause ->
                    PayInStartup.Started(
                        text = probe.text + " The SDK did not start: " + cause.message,
                        isReady = false,
                        payments = null,
                    )
                },
            )
        }
    }
