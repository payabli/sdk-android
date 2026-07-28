package com.payabli.sdk.core.logging.impl

import com.payabli.sdk.core.logging.LogField

/**
 * Turns structured fields into text, deciding per field whether the value may be emitted.
 *
 * The decision is [LoggableFieldNames]'s, not the call site's. An allowlisted value is scrubbed as
 * well, which is deliberate belt and braces: it catches `safe("sid", pan)`.
 */
internal object LogFieldRenderer {
    private const val REDACTED = "[REDACTED]"
    private const val NULL = "[null]"

    /** U+2026 horizontal ellipsis. */
    private const val ELLIPSIS = "…"

    /** Renders [fields] space-separated, or an empty string when there are none. */
    fun render(fields: List<LogField>): String = fields.joinToString(separator = " ") { renderField(it) }

    /**
     * Lowercase and strip non-alphanumerics, so `card-number`, `Card_Number`, `cardNumber` and
     * `card number` all collapse to `cardnumber`.
     *
     * `lowercase()` is locale-independent by design; the deprecated `toLowerCase()` used the default
     * locale, which maps `I` to `ı` under a Turkish locale and would silently break name matching on
     * a Turkish device. Because the check is an allowlist, any normalisation gap fails safe.
     */
    fun normalize(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    private fun renderField(field: LogField): String =
        when (field) {
            is LogField.Safe -> "${field.name}=${renderSafe(field)}"
            is LogField.Redacted -> "${field.name}=" + if (field.wasNull) NULL else REDACTED
            is LogField.LastFour -> "${field.name}=" + renderLastFour(field.tail)
        }

    private fun renderSafe(field: LogField.Safe): String {
        if (normalize(field.name) !in LoggableFieldNames.ALLOWED) return REDACTED
        val scrubbed = SensitiveDataScrubber.scrub(field.rendered)
        return if (scrubbed.any { it.isWhitespace() }) "\"$scrubbed\"" else scrubbed
    }

    private fun renderLastFour(tail: String?): String = if (tail == null) REDACTED else REDACTED + ELLIPSIS + tail
}
