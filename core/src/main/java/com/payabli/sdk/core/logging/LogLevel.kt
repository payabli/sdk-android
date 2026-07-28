package com.payabli.sdk.core.logging

import androidx.annotation.RestrictTo

/**
 * Severity ladder for SDK log records.
 *
 * Declaration order is increasing severity, so the generated [Comparable] is the severity
 * comparison. There is deliberately no `VERBOSE`: the platform documents that verbose
 * "should never be compiled into an application except during development", and a published
 * AAR cannot be recompiled per consumer.
 *
 * The Android priority integers live in `impl/AndroidLogSink.kt` and nowhere else, which keeps
 * this a pure JVM type.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,

    /**
     * An invariant this SDK guarantees was violated.
     *
     * Emitted at `Log.ASSERT` priority via `Log.println`, never via `Log.wtf`: `wtf` may add a
     * DropBoxManager report and may terminate the process, which a payment SDK must never do
     * from a log statement.
     */
    FAULT,
}
