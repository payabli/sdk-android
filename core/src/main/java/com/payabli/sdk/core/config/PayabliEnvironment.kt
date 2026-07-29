package com.payabli.sdk.core.config

/**
 * The Payabli API environment. Determines the base URL every SDK request resolves against.
 *
 * There is deliberately no developer or tunnel environment here: a non-Payabli origin must not be
 * reachable from shipped configuration, not even behind a debug flag.
 *
 * [baseUrl] is a `String` rather than a `java.net.URL` because `URL.equals` and `URL.hashCode` resolve
 * host names, which their own documentation calls a blocking operation.
 */
public enum class PayabliEnvironment(
    /** Origin for this environment, with no trailing path. */
    public val baseUrl: String,
) {
    QA("https://api-qa.payabli.com"),
    SANDBOX("https://api-sandbox.payabli.com"),
    PRODUCTION("https://api.payabli.com"),
}
