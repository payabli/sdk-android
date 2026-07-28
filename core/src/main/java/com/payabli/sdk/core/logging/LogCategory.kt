package com.payabli.sdk.core.logging

import androidx.annotation.RestrictTo

/**
 * Logical origin of a log record.
 *
 * `Log` has a single tag slot, so each category carries a `Payabli`-prefixed tag. The shared prefix
 * makes `adb logcat Payabli*:D *:S` select the whole SDK.
 *
 * Every [tag] must be at most [MAX_TAG_LENGTH] characters: `Log.isLoggable` documents
 * `IllegalArgumentException` for a longer tag on API 25 and below, and this module's floor is 23.
 * `LogCategoryTagTest` enforces it.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public enum class LogCategory(
    /** The logcat tag for this category. Never longer than [MAX_TAG_LENGTH]. */
    public val tag: String,
) {
    CORE("PayabliCore"),
    AUTH("PayabliAuth"),
    NETWORK("PayabliNetwork"),
    TOKENIZATION("PayabliTokenization"),
    TAP_TO_PAY("PayabliTapToPay"),
    TELEMETRY("PayabliTelemetry"),
    ;

    internal companion object {
        /** `Log.isLoggable` throws for a longer tag on API 25 and below; `minSdk` here is 23. */
        internal const val MAX_TAG_LENGTH: Int = 23
    }
}
