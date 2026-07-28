package com.payabli.sdk.core.logging.impl

/**
 * Composes the final line and truncates it to fit one logcat entry.
 *
 * Truncate, do not chunk. Splitting one record across several `Log` calls interleaves with other
 * threads, breaks correlation, and scatters the fragments of a partially scrubbed value across lines
 * where no single line looks alarming. One record, one line, tail dropped; the tail is also the
 * likeliest place a late-appended detail lands.
 *
 * This runs *after* scrubbing, and that order is load-bearing: truncating first could split a secret
 * across the cut and leave a fragment the pattern rules no longer match.
 */
internal object LogRecordFormatter {
    /** logd's per-entry payload ceiling is roughly 4068 bytes including tag and terminators. */
    internal const val MAX_PAYLOAD_BYTES = 3900

    /** Below this, no encoding can exceed the ceiling: UTF-8 costs at most 3 bytes per `Char`. */
    private const val FAST_PATH_CHARS = 1_200

    internal const val TRUNCATION_MARKER = "[TRUNCATED]"

    private const val UTF8_CONTINUATION_MASK = 0xC0
    private const val UTF8_CONTINUATION_BITS = 0x80

    /** [fields] and [throwable] are already rendered and already scrubbed. */
    fun format(
        message: String,
        fields: String,
        throwable: String?,
        tag: String,
    ): String {
        val line =
            buildString {
                append(message)
                if (fields.isNotEmpty()) {
                    append(' ').append(fields)
                }
                if (throwable != null) {
                    append('\n').append(throwable)
                }
            }
        return truncate(line, tag)
    }

    private fun truncate(
        line: String,
        tag: String,
    ): String {
        if (line.length <= FAST_PATH_CHARS) return line
        val budget = MAX_PAYLOAD_BYTES - tag.toByteArray().size
        val bytes = line.toByteArray()
        if (bytes.size <= budget) return line
        var end = budget - TRUNCATION_MARKER.toByteArray().size
        // Step back off a UTF-8 continuation byte so the cut is ours and lands on a code point.
        while (end > 0 && (bytes[end].toInt() and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_BITS) {
            end--
        }
        return String(bytes, 0, end, Charsets.UTF_8) + TRUNCATION_MARKER
    }
}
