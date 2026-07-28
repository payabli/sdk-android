package com.payabli.sdk.core.logging.impl

import com.payabli.sdk.core.logging.LogLevel

/**
 * Where a finished, already-scrubbed, already-truncated line is written.
 *
 * This, not `PayabliLogger`, is the swap point for a different logging backend. Redaction lives
 * above it in [DefaultPayabliLogger], so replacing the sink cannot skip redaction: a future
 * OSLog-shaped bridge, an in-process ring buffer, or a host-facing channel all receive text that has
 * already been through the pipeline.
 *
 * `android.util.Log` is `public final` with static methods, so it cannot be stubbed, and Robolectric
 * is not a dependency of this project. This interface is the reason the unit tests never touch the
 * Android class and never need `unitTests.returnDefaultValues`.
 */
internal interface LogSink {
    fun isLoggable(
        level: LogLevel,
        tag: String,
    ): Boolean

    fun write(
        level: LogLevel,
        tag: String,
        message: String,
    )
}
