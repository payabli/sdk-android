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
 * **The SDK is silent until an integrator opts in.** See [setMinimumLevel].
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
     * over adb, lands in bug reports, and is reachable by any preinstalled app holding `READ_LOGS`. An
     * integrator diagnosing an integration turns it on deliberately and turns it off before release.
     *
     * The platform's per-tag level still applies on top, so `debug` additionally needs
     * `adb shell setprop log.tag.<TAG> DEBUG`. Raising the platform level alone emits nothing.
     */
    public fun setMinimumLevel(level: LogLevel?) {
        minimumLevel = level
    }
}
