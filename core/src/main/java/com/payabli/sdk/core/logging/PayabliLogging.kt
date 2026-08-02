package com.payabli.sdk.core.logging

/**
 * The SDK's logging control, and the only logging surface an app can reach.
 *
 * **The SDK is silent by default.** A shipped payments SDK should not write to logcat unasked: the log is
 * readable over adb, lands in bug reports, and is reachable by any preinstalled app holding `READ_LOGS`.
 *
 * Two things turn it on, and they compose in one direction only:
 *
 * - **A debuggable host app enables it with no code.** An app built for debug gets SDK records automatically.
 * - **[setLevel] always wins over that**, in either call order, so an app that deliberately silences the SDK
 *   with `PayabliLogging.setLevel(LogLevel.NONE)` is not overridden by its own debug build, and an app that
 *   asks for records in a release build gets them. [resetLevel] returns to the automatic behaviour.
 *
 * ```
 * PayabliLogging.setLevel(LogLevel.WARN)   // warnings and worse
 * PayabliLogging.setLevel(LogLevel.NONE)   // silence, overriding a debug build
 * PayabliLogging.resetLevel()              // back to "on if this app is debuggable"
 * ```
 *
 * **Records go to the platform log and nowhere else.** There is deliberately no way to receive them in
 * process: no sink, no callback, no handler. Records handed to an app land in that app's own logging systems,
 * which would extend PCI scope to the integrator, and a callback the SDK invokes could throw, block or
 * re-enter it during a payment.
 *
 * The platform's own per-tag level applies on top of this one. `Log.isLoggable` defaults to `INFO`, so `INFO`
 * and above appear as soon as the level allows them, while `DEBUG` records additionally need
 * `adb shell setprop log.tag.<TAG> DEBUG`.
 *
 * Every record is redacted before it is written: values travel as typed fields whose names are checked against
 * a deny-by-default allowlist, and a scrubber backstops what is left. Turning logging on cannot surface a PAN,
 * a token or a credential.
 */
public object PayabliLogging {
    /** Emits [level] and everything more severe. [LogLevel.NONE] silences the SDK. */
    public fun setLevel(level: LogLevel) {
        PayabliLoggers.setLogLevel(level)
    }

    /** Drops an explicit [setLevel], so the SDK returns to logging only when the host app is debuggable. */
    public fun resetLevel() {
        PayabliLoggers.clearLogLevel()
    }
}
