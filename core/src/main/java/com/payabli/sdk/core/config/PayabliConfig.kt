package com.payabli.sdk.core.config

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException

private const val REASON_MISSING_ACCESS_TOKEN = "accessToken must not be blank"
private const val REASON_MISSING_ENTRY_POINT = "entryPoint must not be blank"
private const val REASON_UNUSABLE_ACCESS_TOKEN = "accessToken must be usable as an HTTP header value"

/**
 * The values a host supplies once, in one place, for every Payabli SDK component.
 *
 * The auth holder reads this and calls [tokenProvider] when a token is rejected. The transport that
 * detects a rejection arrives later in this phase, so nothing reaches that path yet.
 *
 * The host holding [accessToken] is temporary. It is minted by the host's own backend, so the client
 * secret never reaches the app binary, and it lives here and nowhere else: never logged, never
 * persisted. This mirrors the shipping iOS SDK for one shared baseline; the target design moves token
 * custody inside the SDK.
 */
public class PayabliConfig(
    /** Pre-minted bearer token from the host app's backend. Never logged, never persisted. */
    public val accessToken: String,
    /** The partner integration point, the platform's `entryName`. */
    public val entryPoint: String,
    /** Selects the base URL for every request. */
    public val environment: PayabliEnvironment,
    /** Called by the auth holder when a token is rejected. Null makes a rejection terminal. */
    public val tokenProvider: PayabliTokenProvider? = null,
    /** Read by the telemetry module when it lands. On by default, so switching it off is deliberate. */
    public val telemetryEnabled: Boolean = true,
) {
    init {
        if (accessToken.isBlank()) {
            throw PayabliGenericException(PayabliErrorCode.INVALID_CONFIGURATION, REASON_MISSING_ACCESS_TOKEN)
        }
        if (!accessToken.isHeaderSafe()) {
            throw PayabliGenericException(PayabliErrorCode.INVALID_CONFIGURATION, REASON_UNUSABLE_ACCESS_TOKEN)
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
