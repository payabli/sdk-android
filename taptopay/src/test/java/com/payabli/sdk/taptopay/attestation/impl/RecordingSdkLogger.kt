package com.payabli.sdk.taptopay.attestation.impl

import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.core.logging.SdkLogger

/**
 * Captures log calls made from this module.
 *
 * Not `RecordingLogSink`, which is `:core`'s and reachable only from `:core`'s own compilations: `LogSink`
 * and `DefaultSdkLogger` are `internal`, so a sibling module cannot see them and must not be given a reason
 * to widen a published security SDK's surface to suit a test layout.
 *
 * The consequence is that this records **field names, not rendered values**: the renderer and its
 * allowlist are `:core` internals too. That turns out to be the better assertion. Whether an allowlisted
 * value renders correctly is `:core`'s question and `:core` tests it; what this module owes is that the
 * attestation path passes exactly the fields it means to and never hands a challenge or a token to the
 * logger at all. A name set is the direct expression of that.
 */
internal class RecordingSdkLogger : SdkLogger {
    internal data class Record(
        val level: LogLevel,
        val fieldNames: List<String>,
        val message: String,
    )

    val records: MutableList<Record> = mutableListOf()

    override fun isLoggable(level: LogLevel): Boolean = true

    override fun log(
        level: LogLevel,
        fields: List<LogField>,
        throwable: Throwable?,
        message: () -> String,
    ) {
        // A list, not a set: a set erases a repeated name, so an assertion reading "exactly these
        // three" would still pass if a fourth field reused one of them.
        records += Record(level, fields.map { it.name }, message())
    }
}
