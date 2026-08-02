package com.payabli.sdk.core.logging.impl

import com.payabli.sdk.core.logging.LogLevel

/**
 * The SDK's log cutoff, held in two independent slots so an explicit setting always beats the
 * automatic one whichever order they arrive in.
 *
 * Silence is [LogLevel.NONE] rather than null, which is what makes [effective] a single honest
 * expression: with null standing for both "never set" and "deliberately silent", the obvious
 * `explicit ?: automatic` would fall through to the automatic value exactly when a caller had
 * asked for silence.
 *
 * Each slot is one volatile store and there is no invariant spanning the two, so this needs no
 * lock and no `AtomicReference`.
 */
internal class LogLevelSetting {
    /** Null means no explicit setting. The only nullable here, and it crosses no API boundary. */
    @Volatile
    private var explicit: LogLevel? = null

    @Volatile
    private var automatic: LogLevel = LogLevel.NONE

    fun effective(): LogLevel = explicit ?: automatic

    fun setExplicit(level: LogLevel) {
        explicit = level
    }

    fun clearExplicit() {
        explicit = null
    }

    fun setHostDebuggable(debuggable: Boolean) {
        automatic = if (debuggable) LogLevel.DEBUG else LogLevel.NONE
    }
}
