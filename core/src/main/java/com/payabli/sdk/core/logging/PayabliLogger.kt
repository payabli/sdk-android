package com.payabli.sdk.core.logging

import androidx.annotation.RestrictTo

/**
 * The SDK's only logging entry point. No Payabli code calls `android.util.Log` directly; the single
 * file that does is `impl/AndroidLogSink.kt`.
 *
 * Two abstract members, so a test fake costs two overrides. The five level methods are extension
 * functions in `PayabliLoggerLevels.kt` rather than interface members: extensions are not virtual,
 * so no implementation can make `debug` bypass the redaction pipeline that [log] applies.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface PayabliLogger {
    /**
     * Whether [level] would be emitted. Two gates, both of which must allow it: the SDK's own floor,
     * which `PayabliLoggers.setMinimumLevel` controls and which is silent by default, and the platform's
     * per-tag level. So `adb shell setprop log.tag.<TAG> DEBUG` alone emits nothing.
     */
    public fun isLoggable(level: LogLevel): Boolean

    /**
     * The single primitive.
     *
     * [message] is a lambda so a disabled level costs nothing and, more importantly, so a sensitive
     * interpolation is never built when the level is off. Runtime values belong in [fields], never
     * interpolated into [message]; the message is scrubbed on a best-effort basis but scrubbing is a
     * net, not a contract.
     *
     * [throwable] is rendered by the SDK, not by `Log.getStackTraceString`: frames are kept, every
     * `Throwable.message` in the cause chain is scrubbed, and the chain is depth-capped.
     */
    public fun log(
        level: LogLevel,
        fields: List<LogField>,
        throwable: Throwable?,
        message: () -> String,
    )
}
