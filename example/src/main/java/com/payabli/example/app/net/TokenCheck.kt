package com.payabli.example.app.net

/**
 * What a token check concluded, for the step that reports it.
 *
 * @param text what the payer-facing line says, marked by the probe itself.
 * @param reachable the endpoint answered with a token.
 */
data class TokenCheckOutcome(
    val text: String,
    val reachable: Boolean,
)

/**
 * Asks the token endpoint for a token, and reports only that one arrived.
 *
 * A form that submits before the backend answers fails at the end of a filled-in card instead of
 * the start of an empty one.
 */
suspend fun TokenServerClient.checkToken(): TokenCheckOutcome {
    val outcome = probeAccessToken()
    return TokenCheckOutcome(
        text = outcome.displayText(TokenServerProbe.TOKEN_LABEL),
        reachable = outcome is TokenServerProbe.Ok,
    )
}
