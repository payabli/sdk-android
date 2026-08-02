package com.payabli.sdk.core.logging.impl

import android.util.Log
import com.payabli.sdk.core.logging.LogLevel

/** The only file in this repository that imports `android.util.Log`. */
internal object AndroidLogSink : LogSink {
    override fun isLoggable(
        level: LogLevel,
        tag: String,
    ): Boolean = Log.isLoggable(tag, level.priority())

    // Log.println for every level, including FAULT. Log.wtf is not used: it may add a
    // DropBoxManager report and may terminate the process, which a log statement in a payment
    // SDK must never be able to do.
    override fun write(
        level: LogLevel,
        tag: String,
        message: String,
    ) {
        Log.println(level.priority(), tag, message)
    }

    private fun LogLevel.priority(): Int =
        when (this) {
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
            LogLevel.FAULT -> Log.ASSERT
            // Unreachable: DefaultSdkLogger rejects a non-record level before the sink is
            // reached. Mapped rather than thrown, for the same reason Log.wtf is not used.
            LogLevel.NONE -> Log.ASSERT
        }
}
