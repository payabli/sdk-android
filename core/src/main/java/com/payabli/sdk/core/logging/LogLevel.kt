package com.payabli.sdk.core.logging

/**
 * Severity ladder for SDK log records, plus [NONE] as the cutoff that admits nothing.
 *
 * Public because it appears in `PayabliSession.setLogLevel`, which is the app-facing control.
 *
 * Declaration order is increasing severity, so the generated [Comparable] is the severity
 * comparison. There is deliberately no `VERBOSE`: the platform documents that verbose
 * "should never be compiled into an application except during development", and a published
 * AAR cannot be recompiled per consumer.
 *
 * The Android priority integers live in `impl/AndroidLogSink.kt` and nowhere else, which keeps
 * this a pure JVM type.
 */
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

    /**
     * Not a severity, and never the level of a record: the cutoff that admits nothing.
     *
     * Declared last so `level >= NONE` is false for every record level, which is what makes it
     * mean "silent". Do not add a value after it, and do not reorder this enum: the ordering *is*
     * the comparison, and `LogLevelOrderTest` fails if either changes.
     */
    NONE,
    ;

    /** False only for [NONE]. Named once here rather than compared inline at each guard. */
    internal val isRecordLevel: Boolean get() = this != NONE
}
