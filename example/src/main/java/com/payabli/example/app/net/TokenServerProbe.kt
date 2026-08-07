package com.payabli.example.app.net

/**
 * What a probe of the local token server found.
 *
 * A type, so the mapping from outcome to the line shown on screen is something a unit test can pin.
 * The socket work in [TokenServerClient] is not covered by a test. This is, and it is where the
 * wording lives.
 */
sealed interface TokenServerProbe {
    /** The endpoint answered as expected. [detail] carries what came back, never a secret. */
    data class Ok(
        val detail: String,
    ) : TokenServerProbe

    /** The endpoint answered, but not with success. */
    data class HttpStatus(
        val code: Int,
    ) : TokenServerProbe

    /** Nothing answered. [message] is the transport's own words. */
    data class Unreachable(
        val message: String,
    ) : TokenServerProbe

    companion object {
        const val TOKEN_LABEL: String = "Token endpoint"
        const val HEALTH_LABEL: String = "Local token server"
    }
}

/**
 * The one line the Setup and Tap to pay screens show for a probe.
 *
 * A check mark or a cross, so the outcome survives a screenshot, a colour-blind reader and a
 * monochrome display.
 */
fun TokenServerProbe.displayText(label: String): String =
    when (this) {
        is TokenServerProbe.Ok -> "✓ $label $detail"
        is TokenServerProbe.HttpStatus -> "✗ $label returned HTTP $code"
        is TokenServerProbe.Unreachable -> "✗ $label unreachable: $message"
    }
