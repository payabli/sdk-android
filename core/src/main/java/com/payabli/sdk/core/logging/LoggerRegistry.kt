package com.payabli.sdk.core.logging

import androidx.annotation.RestrictTo
import com.payabli.sdk.core.logging.impl.AndroidLogSink
import com.payabli.sdk.core.logging.impl.DefaultSdkLogger
import com.payabli.sdk.core.logging.impl.LogLevelSetting

/**
 * Default wiring. Every SDK class takes a [SdkLogger] as a constructor parameter and defaults it
 * here, so production call sites carry no boilerplate and tests inject a fake:
 *
 * ```
 * internal class PayabliService(
 *     private val logger: SdkLogger = LoggerRegistry.of(LogCategory.NETWORK),
 * )
 * ```
 *
 * A small service locator rather than a DI framework, which the dependency policy bars. Instances are
 * stateless and shared.
 *
 * **The SDK is silent until [setLogLevel] or [setHostDebuggable] lowers the cutoff.** Neither is the app-facing
 * control: an integrator reaches `PayabliSession.setLogLevel` instead, which delegates here.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object LoggerRegistry {
    private val logLevel = LogLevelSetting()

    private val loggers: Map<LogCategory, SdkLogger> =
        LogCategory.entries.associateWith { DefaultSdkLogger(it, AndroidLogSink, logLevel::effective) }

    /**
     * The shared, stateless logger for [category]. Reads the current cutoff on every call.
     *
     * @param category the category the logger reports under.
     * @return the shared, stateless logger for [category].
     */
    public fun of(category: LogCategory): SdkLogger = loggers.getValue(category)

    /**
     * Emits [level] and everything more severe; [LogLevel.NONE] silences the SDK, which is the default.
     * Silent by default because a shipped payments SDK should not write to logcat. An explicit setting
     * always wins over the automatic one from [setHostDebuggable], in either call order.
     *
     * @param level the cutoff to emit at and above.
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
     * `DEBUG` rather than `INFO` so the SDK's own cutoff stops filtering entirely and the platform's
     * per-tag level is the only control left.
     *
     * @param debuggable whether the host app's build is debuggable.
     */
    internal fun setHostDebuggable(debuggable: Boolean) {
        logLevel.setHostDebuggable(debuggable)
    }

    /**
     * The cutoff in effect. Internal so the precedence wiring is provable on the JVM, where the real
     * sink cannot run.
     *
     * @return the cutoff in effect, the explicit setting over the automatic one.
     */
    internal fun effectiveLogLevel(): LogLevel = logLevel.effective()
}
