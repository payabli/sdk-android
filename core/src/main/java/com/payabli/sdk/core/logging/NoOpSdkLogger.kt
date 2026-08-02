package com.payabli.sdk.core.logging

import androidx.annotation.RestrictTo

/**
 * A logger that emits nothing and never invokes the message lambda.
 *
 * For tests and for the rare production path that must be provably silent. It lives in the contract
 * package rather than `impl` because it is annotated cross-artifact surface, and the review rule for
 * these two packages is that everything here carries [RestrictTo] and nothing in `impl` does.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object NoOpSdkLogger : SdkLogger {
    override fun isLoggable(level: LogLevel): Boolean = false

    override fun log(
        level: LogLevel,
        fields: List<LogField>,
        throwable: Throwable?,
        message: () -> String,
    ) {
        // Deliberately empty. The lambda is not invoked, so nothing sensitive is ever composed.
    }
}
