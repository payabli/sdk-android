package com.payabli.sdk.core.logging

import androidx.annotation.RestrictTo
import com.payabli.sdk.core.logging.impl.AndroidLogSink
import com.payabli.sdk.core.logging.impl.DefaultPayabliLogger
import com.payabli.sdk.core.logging.impl.LogLevelSetting

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
 * **The SDK is silent until [setLogLevel] or [setHostDebuggable] lowers the cutoff.** Neither is the app-facing
 * control: an integrator reaches [PayabliLogging] instead, which delegates here.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object PayabliLoggers {
    private val logLevel = LogLevelSetting()

    private val loggers: Map<LogCategory, PayabliLogger> =
        LogCategory.entries.associateWith { DefaultPayabliLogger(it, AndroidLogSink, logLevel::effective) }

    /** The shared, stateless logger for [category]. Reads the current cutoff on every call. */
    public fun of(category: LogCategory): PayabliLogger = loggers.getValue(category)

    /**
     * Emits [level] and everything more severe. [LogLevel.NONE] silences the SDK, which is the default.
     *
     * Silent by default because a shipped payments SDK should not write to logcat: the log is readable
     * over adb, lands in bug reports, and is reachable by any preinstalled app holding `READ_LOGS`.
     *
     * **An explicit setting always wins over the automatic one from [setHostDebuggable]**, in either
     * call order, so a caller that deliberately silenced the SDK is not overridden. [clearLogLevel]
     * is the way back.
     *
     * `internal`: [PayabliLogging] is the app-facing control and delegates here, so this stays the SDK's
     * own entry point rather than a second public one. What is exposed is the level; the sink is not, which
     * is what keeps records out of an app's own logging systems.
     *
     * The platform's per-tag level still applies on top, so `debug` additionally needs
     * `adb shell setprop log.tag.<TAG> DEBUG`. Lowering this cutoff alone emits nothing at `debug`.
     */
    internal fun setLogLevel(level: LogLevel) {
        logLevel.setExplicit(level)
    }

    /** Drops the explicit setting, so the automatic value from [setHostDebuggable] applies again. */
    internal fun clearLogLevel() {
        logLevel.clearExplicit()
    }

    /**
     * The automatic cutoff: [LogLevel.DEBUG] when the host app is debuggable, [LogLevel.NONE] otherwise.
     *
     * `DEBUG` rather than `INFO` so the SDK's own cutoff stops filtering entirely and the platform's
     * per-tag level is the only control left. Internal because only the SDK's own entry point should
     * decide it; `logging/platform/HostLogLevel.kt` reads the flag it takes.
     */
    internal fun setHostDebuggable(debuggable: Boolean) {
        logLevel.setHostDebuggable(debuggable)
    }

    /**
     * The cutoff in effect. Internal so the precedence wiring is provable on the JVM, where the real
     * sink cannot run.
     */
    internal fun effectiveLogLevel(): LogLevel = logLevel.effective()
}
