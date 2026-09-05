package com.payabli.sdk.core.config

import com.payabli.sdk.core.model.PayabliErrorCode
import com.payabli.sdk.core.model.PayabliGenericException

private const val REASON_MISSING_ENTRY_POINT = "entryPoint must not be blank"

/**
 * The values a host supplies once, in one place, for every Payabli SDK component.
 *
 * **No credential is passed in.** The host supplies [tokenProvider] and the SDK calls it when it needs a
 * token, so nothing here is a secret and there is one way for a token to arrive rather than two. A token
 * is minted by the host's own backend, which is what keeps the client secret out of the app binary.
 */
public class PayabliConfig(
    /** The partner integration point, the platform's `entryName`. */
    public val entryPoint: String,
    /** Selects the base URL for every request. */
    public val environment: PayabliEnvironment,
    /**
     * Called when the SDK needs a token: once before the first request, and again whenever one is
     * rejected. Required, because it is the only way a token reaches the SDK.
     */
    public val tokenProvider: PayabliTokenProvider,
    /** Read by the telemetry module. On by default, so switching it off is deliberate. */
    public val telemetryEnabled: Boolean = true,
) {
    init {
        if (entryPoint.isBlank()) {
            throw PayabliGenericException(PayabliErrorCode.INVALID_CONFIGURATION, REASON_MISSING_ENTRY_POINT)
        }
    }

    /**
     * Carries no identifier. `entryPoint` is withheld because it names a specific merchant, and this
     * string reaches exception messages and crash reports.
     */
    override fun toString(): String = "PayabliConfig(environment=$environment, telemetryEnabled=$telemetryEnabled)"
}
