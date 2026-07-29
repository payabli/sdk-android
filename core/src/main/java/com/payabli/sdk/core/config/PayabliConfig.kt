package com.payabli.sdk.core.config

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException

private const val REASON_MISSING_ACCESS_TOKEN = "accessToken must not be blank"
private const val REASON_MISSING_ENTRY_POINT = "entryPoint must not be blank"

/**
 * The values a host supplies once, in one place, for every Payabli SDK component.
 *
 * **Nothing reads this type yet.** It is the configuration surface by itself: the auth session, the
 * authenticated transport, and the components that consume it all arrive later in this phase. What is
 * settled here is the shape a host has to satisfy, not any behaviour behind it.
 *
 * ## The host holds the token in this phase, and that is temporary
 *
 * [accessToken] is minted by the host app's **own backend** against Payabli's server-side token
 * endpoint, so the client secret never reaches the app binary. It is held on this object and nowhere
 * else: never logged, never persisted.
 *
 * This mirrors the shipping iOS SDK so both platforms share one baseline. It is not the intended end
 * state: the target design moves token custody inside the SDK, where the host names and observes but
 * never holds a credential. A host passing a token here is a property of this phase, not the contract to
 * build against long term.
 *
 * ## Refresh is reserved, not implemented
 *
 * [tokenProvider] is accepted and stored, and nothing calls it. Every 401 surfaces as
 * [PayabliErrorCode.TOKEN_EXPIRED] whether or not a provider was supplied, so supplying one currently
 * changes nothing. Refresh-and-retry arrives with the authenticated transport that wraps this config.
 */
public class PayabliConfig(
    /** Pre-minted bearer token from the host app's backend. Never logged, never persisted. */
    public val accessToken: String,
    /** The partner integration point, the platform's `entryName`. */
    public val entryPoint: String,
    /** Selects the base URL for every request. */
    public val environment: PayabliEnvironment,
    /** Reserved for the refresh path. Stored only; nothing calls it yet. */
    public val tokenProvider: PayabliTokenProvider? = null,
    /** Read by the telemetry module when it lands. On by default, so switching it off is deliberate. */
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
