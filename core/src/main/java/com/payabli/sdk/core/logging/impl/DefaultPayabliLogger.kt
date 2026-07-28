package com.payabli.sdk.core.logging.impl

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.core.logging.PayabliLogger

/**
 * The redaction pipeline. Every collaborator is a stateless `object` whose regexes are compiled at
 * class initialisation, so this class holds no mutable state and needs no synchronisation. Do not add
 * a lock, and do not add a cache.
 *
 * Step order is load-bearing: scrub before truncate, so truncation only ever shortens text that is
 * already safe.
 */
internal class DefaultPayabliLogger(
    private val category: LogCategory,
    private val sink: LogSink,
    /**
     * The SDK's own floor, read on every call so raising it later affects loggers already handed out.
     * Null silences the SDK entirely. Defaults to fully verbose, which is what a test wants;
     * `PayabliLoggers` supplies the configured gate in production.
     */
    private val minimumLevel: () -> LogLevel? = { LogLevel.DEBUG },
) : PayabliLogger {
    override fun isLoggable(level: LogLevel): Boolean {
        val floor = minimumLevel() ?: return false
        return level >= floor && sink.isLoggable(level, category.tag)
    }

    override fun log(
        level: LogLevel,
        fields: List<LogField>,
        throwable: Throwable?,
        message: () -> String,
    ) {
        if (!isLoggable(level)) return
        val head = SensitiveDataScrubber.scrub(message())
        val body = LogFieldRenderer.render(fields)
        val trace = throwable?.let { ThrowableRenderer.render(it) }
        sink.write(level, category.tag, LogRecordFormatter.format(head, body, trace, category.tag))
    }
}
