package com.payabli.sdk.core.config

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException

private const val REASON_MISSING_ACCESS_TOKEN = "accessToken must not be blank"
private const val REASON_MISSING_ENTRY_POINT = "entryPoint must not be blank"

/**
 * Shared configuration for every Payabli SDK component. One instance is reused across components, which
 * is how they end up sharing one auth session rather than authenticating separately.
 *
 * ## The host holds the token in this phase, and that is temporary
 *
 * [accessToken] is minted by the host app's **own backend** against Payabli's server-side token
 * endpoint, so the client secret never reaches the app binary. The SDK sends it as a bearer credential
 * and holds it in memory only.
 *
 * This mirrors the shipping iOS SDK so both platforms share one baseline. It is not the intended end
 * state: the target design moves token custody inside the SDK, where the host names and observes but
 * never holds a credential. A host passing a token here is a property of this phase, not the contract to
 * build against long term.
 *
 * Supplying a [tokenProvider] is what lets the SDK recover from an expired token. Without one, an
 * expired token surfaces as [PayabliErrorCode.TOKEN_EXPIRED] and the caller has to start again.
 */
public class PayabliConfig(
    /** Pre-minted bearer token from the host app's backend. Never logged, never persisted. */
    public val accessToken: String,
    /** The partner integration point, the platform's `entryName`. */
    public val entryPoint: String,
    /** Selects the base URL for every request. */
    public val environment: PayabliEnvironment,
    /** Called when a token is rejected. Null means an expired token is terminal for the caller. */
    public val tokenProvider: PayabliTokenProvider? = null,
    /** Emits SDK telemetry. On by default, so switching it off is the host's explicit choice. */
    public val telemetryEnabled: Boolean = true,
) {
    init {
        if (accessToken.isBlank()) {
            throw PayabliGenericException(PayabliErrorCode.INVALID_CONFIGURATION, REASON_MISSING_ACCESS_TOKEN)
        }
        if (entryPoint.isBlank()) {
            throw PayabliGenericException(PayabliErrorCode.INVALID_CONFIGURATION, REASON_MISSING_ENTRY_POINT)
        }
    }

    /**
     * Carries no credential and no identifier. `entryPoint` is withheld as well as the token: it names a
     * specific merchant, and this string reaches exception messages and crash reports.
     */
    override fun toString(): String =
        "PayabliConfig(environment=$environment, telemetryEnabled=$telemetryEnabled, " +
            "tokenProvider=${if (tokenProvider == null) "absent" else "present"})"
}
