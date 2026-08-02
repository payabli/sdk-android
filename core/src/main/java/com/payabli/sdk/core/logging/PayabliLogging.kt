package com.payabli.sdk.core.logging

/**
 * The SDK's diagnostic log control, and the only logging surface an app can reach.
 *
 * Specified by SEC-001 Section 5.3, which fixes the shape of this control rather than leaving it to the
 * implementation. Everything below is that contract, not a local choice.
 *
 * **The SDK emits nothing until [setLogLevel] is called.**
 *
 * ```
 * PayabliLogging.setLogLevel(LogLevel.WARN)   // warnings and worse
 * PayabliLogging.setLogLevel(LogLevel.NONE)   // silence again
 * ```
 *
 * **Records go to the platform log and nowhere else.** There is deliberately no way to receive them in
 * process: no sink, no callback, no handler. Section 5.3 refuses one on the ground that decides it, that
 * records handed to an app land in the integrator's own logging estate, which is precisely the scope an
 * integrator adopts this SDK to stay out of. A record written to the platform log does not cross the App and
 * SDK boundary and so does not carry that consequence.
 *
 * The platform's own per-tag level applies on top of this one, so setting a level lifts the SDK's cutoff
 * rather than guaranteeing every record is written. `Log.isLoggable` defaults to `INFO`, so `INFO` and above
 * appear as soon as the level admits them, while `DEBUG` records additionally need
 * `adb shell setprop log.tag.<TAG> DEBUG`.
 *
 * This control governs **whether** records are emitted, never **what** they may contain (Section 9.5). Every
 * record is redacted before it is written, so turning logging on cannot surface a PAN, a token or a
 * credential.
 */
public object PayabliLogging {
    /**
     * Emits [level] and everything more severe. [LogLevel.NONE] silences the SDK.
     *
     * Type-level rather than an instance member, and forced rather than chosen: an instance-owned setter
     * could not be called before `initialize`, which is where SEC-001 Section 5.3 has an automatic value
     * derived, so "explicit beats automatic in either order" would hold in only one direction.
     *
     * There is deliberately no way back to an unset level. Section 5.3's surface is this setter and nothing
     * else, and an app that has decided its level has no reason to un-decide it.
     */
    public fun setLogLevel(level: LogLevel) {
        LoggerRegistry.setLogLevel(level)
    }
}
