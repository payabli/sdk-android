package com.payabli.sdk.core.logging.impl

/**
 * Renders a throwable for a log record.
 *
 * `<class>: <scrubbed message>` per throwable, plus `at <frame>` lines, plus a `Caused by:` chain
 * capped at [MAX_CAUSE_DEPTH]. Frames are kept: a class, method, file and line number carry no
 * cardholder data and are the whole value of a trace. Messages are scrubbed, because
 * `NumberFormatException: For input string: "..."` echoes its input verbatim, which is exactly how a
 * PAN reaches a log.
 *
 * `Log.getStackTraceString` is not used, for two independent reasons: it renders messages unscrubbed,
 * and it returns an empty string when any throwable in the cause chain is an `UnknownHostException`,
 * silently discarding the most common transport failure.
 */
internal object ThrowableRenderer {
    internal const val MAX_CAUSE_DEPTH = 5
    private const val MAX_FRAMES_PER_THROWABLE = 12
    private const val CAUSE_PREFIX = "Caused by: "

    /** Returns already-scrubbed text; callers must not scrub it a second time. */
    fun render(throwable: Throwable): String =
        buildString {
            var current: Throwable? = throwable
            var causeDepth = 0
            while (current != null) {
                if (causeDepth > 0) {
                    append('\n').append(CAUSE_PREFIX)
                }
                appendThrowable(current)
                val next = current.cause
                current = if (next === current || causeDepth == MAX_CAUSE_DEPTH) null else next
                causeDepth++
            }
        }

    private fun StringBuilder.appendThrowable(throwable: Throwable) {
        append(throwable.javaClass.name)
        val message = throwable.message
        if (!message.isNullOrEmpty()) {
            append(": ").append(SensitiveDataScrubber.scrub(message))
        }
        throwable.stackTrace.take(MAX_FRAMES_PER_THROWABLE).forEach {
            append("\n\tat ").append(it.toString())
        }
    }
}
