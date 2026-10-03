package com.payabli.sdk.core.model

/**
 * What a host does about a failure. Every [PayabliErrorCode] belongs to exactly one category, and each
 * category is one remedy, so a host chooses its response by switching on this rather than on the code.
 *
 * [wireName] is what telemetry and support tooling match on; the explicit property follows
 * [PayabliErrorCode.wireName] for the same reason.
 */
public enum class PayabliErrorCategory(
    public val wireName: String,
) {
    /** The SDK's credential, a token or this device's identity, has to be established again: call `initialize`. */
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
