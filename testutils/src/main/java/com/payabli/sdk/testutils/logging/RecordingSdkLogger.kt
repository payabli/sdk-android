package com.payabli.sdk.testutils.logging

import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.core.logging.SdkLogger

/**
 * Captures log calls instead of making them.
 *
 * It sees what a caller handed over, not what would have been printed: the renderer and its allowlist sit
 * below this interface. That is the better assertion for a caller anyway. Whether an allowlisted value
 * renders correctly belongs to the module that owns the renderer; what a caller owes is that it passes
 * exactly the fields it means to and never hands a secret to the logger at all.
 *
 * A test whose subject *is* the redaction pipeline needs a fixture below this interface rather than this one.
 */
public class RecordingSdkLogger : SdkLogger {
    public data class Record(
        val level: LogLevel,
        val fields: List<LogField>,
        val message: String,
        /**
         * The throwable as it was handed over, so a test can assert what a caller attaches.
         *
         * The renderer prints a throwable's message, and an exception raised over decrypted data can carry
         * that data in it. Whether a caller redacted its cause is therefore assertable only here.
         */
        val throwable: Throwable? = null,
    ) {
        /**
         * A list, not a set: a set erases a repeated name, so an assertion reading "exactly these three"
         * would still pass if a fourth field reused one of them.
         */
        public val fieldNames: List<String> get() = fields.map { it.name }
    }

    public val records: MutableList<Record> = mutableListOf()

    /** Everything, so a test sees every record the SDK writes. */
    override fun isLoggable(level: LogLevel): Boolean = true

    override fun log(
        level: LogLevel,
        fields: List<LogField>,
        throwable: Throwable?,
        message: () -> String,
    ) {
        records += Record(level, fields, message(), throwable)
    }

    /** Every field and every message, flattened, for asserting that a value never appears anywhere. */
    public fun everythingWritten(): String =
        records.joinToString(" ") { record ->
            record.message + " " + record.fields.joinToString(" ") { it.toString() }
        }
}
