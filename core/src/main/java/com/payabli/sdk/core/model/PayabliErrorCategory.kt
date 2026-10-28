package com.payabli.sdk.core.model

/**
 * What a host does about a failure. Every [PayabliErrorType] belongs to exactly one category, and each
 * category is one remedy, so a host chooses its response by switching on this rather than on the code.
 *
 * [wireName] is what telemetry and support tooling match on; the explicit property follows
 * [PayabliErrorType.wireName] for the same reason.
 */
public enum class PayabliErrorCategory(
    public val wireName: String,
) {
    /**
     * The SDK could not obtain or use a credential, a token or this device's setup.
     *
     * A provider that failed is asked again on the next call. [PayabliErrorType.TOKEN_EXPIRED] means a fresh
     * token was refused too, so the session is finished until `initialize`, once the cause is fixed. It leaves
     * the session usable when another call had already replaced the refused token, or was replacing it, and when
     * a Tap to Pay device setup's own request was refused after the renewal, where the next setup call asks
     * again. A setup call that fails with it at once, without calling the provider, means the session is
     * finished: call `initialize`, then create the terminal again from the new session. A device setup that has
     * ended, which the state reports, is established again by calling `initialize`.
     */
    CREDENTIAL("CREDENTIAL"),

    /** The same call may work later. */
    RETRY_LATER("RETRY_LATER"),

    /** The call may have taken effect, so check before repeating it. */
    OUTCOME_UNKNOWN("OUTCOME_UNKNOWN"),

    /** Someone changes the setup. */
    CONFIGURATION("CONFIGURATION"),

    /** The request has to change. */
    INVALID_REQUEST("INVALID_REQUEST"),

    /** Do not repeat it. */
    DECLINED("DECLINED"),

    /** This handset cannot do it. */
    DEVICE("DEVICE"),

    /** Report it. */
    INTERNAL("INTERNAL"),
}
