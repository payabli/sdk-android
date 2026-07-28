package com.payabli.sdk.core.logging

import androidx.annotation.RestrictTo
import com.payabli.sdk.core.logging.impl.AndroidLogSink
import com.payabli.sdk.core.logging.impl.DefaultPayabliLogger

/**
 * Default wiring. Every SDK class takes a [PayabliLogger] as a constructor parameter and defaults it
 * here, so production call sites carry no boilerplate and tests inject a fake:
 *
 * ```
 * internal class PayabliService(
 *     private val logger: PayabliLogger = PayabliLoggers.of(LogCategory.NETWORK),
 * )
 * ```
 *
 * A small service locator rather than a DI framework, which the dependency policy bars. Instances are
 * stateless and shared.
 *
 * **The SDK is silent until [setMinimumLevel] raises it**, which is an SDK-internal control rather than an
 * integrator-facing one.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object PayabliLoggers {
    @Volatile
    private var minimumLevel: LogLevel? = null

    private val loggers: Map<LogCategory, PayabliLogger> =
        LogCategory.entries.associateWith { DefaultPayabliLogger(it, AndroidLogSink) { minimumLevel } }

    /** The shared, stateless logger for [category]. Reads the current level on every call. */
    public fun of(category: LogCategory): PayabliLogger = loggers.getValue(category)

    /**
     * Raises SDK logging to [level] and above. Null silences it again, which is the default.
     *
     * Silent by default because a shipped payments SDK should not write to logcat: the log is readable
     * over adb, lands in bug reports, and is reachable by any preinstalled app holding `READ_LOGS`.
     *
     * **This is SDK-internal, not an integrator-facing control.** The type is `@RestrictTo`, so an app
     * calling it fails lint. Exposing logging configuration to a host app is a public-surface decision and
     * arrives with the public configuration type; until then only `:core` and its siblings can raise the
     * level, and a developer diagnosing a build does it from a debug harness inside the SDK's own group.
     *
     * The platform's per-tag level still applies on top, so `debug` additionally needs
     * `adb shell setprop log.tag.<TAG> DEBUG`. Raising the platform level alone emits nothing.
     */
    public fun setMinimumLevel(level: LogLevel?) {
        minimumLevel = level
    }
}
