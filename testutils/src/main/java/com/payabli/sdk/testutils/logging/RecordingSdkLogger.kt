package com.payabli.sdk.testutils.logging

import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LogLevel
import com.payabli.sdk.core.logging.SdkLogger
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList

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

    /**
     * Thread-safe, because a transport under test writes from every request in flight at once, and the
     * auth holder this module's `testAuth` builds logs from whichever caller is refreshing.
     *
     * An `ArrayList` here loses records rather than reporting anything, and the same race throws out of
     * `ArrayList.add` often enough to redden a run with nothing wrong with it. `RecordingLogSink` is a
     * `CopyOnWriteArrayList` for the same reason.
     *
     * Copy-on-write also gives every reader a snapshot iterator, which is what makes an assertion safe to
     * run while a transport under test is still writing. A lock-guarded list would need each of those
     * readers to hold the lock, and they are ordinary `single` and `filter` calls in other modules.
     */
    private val written: MutableList<Record> = CopyOnWriteArrayList()

    /**
     * Read-only, because a caller that can clear or append changes the answer its own assertion is about
     * to read. Only [log] writes here.
     *
     * Wrapped rather than only typed as `List`: the declared type is erased at runtime, so a cast reaches
     * the backing store and the guarantee would hold only for callers who were not trying. Still a view of
     * the same list rather than a copy of it, so a reader that took it before a write sees the write.
     */
    public val records: List<Record> get() = Collections.unmodifiableList(written)

    /** Everything, so a test sees every record the SDK writes. */
    override fun isLoggable(level: LogLevel): Boolean = true

    override fun log(
        level: LogLevel,
        fields: List<LogField>,
        throwable: Throwable?,
        message: () -> String,
    ) {
        // Copied, so a caller that hands over a list it still holds cannot change what was recorded after
        // the fact. A record is what was logged at the moment it was logged.
        written += Record(level, fields.toList(), message(), throwable)
    }

    /**
     * Every message, every field **name** and every attached throwable, flattened, for asserting that a
     * value never appears in one of them.
     *
     * **Field values are not in here and cannot be.** A field's value lives on a subtype of [LogField]
     * that is internal to the module declaring it, so nothing outside that module can read one, and
     * `toString` on the field yields object identity rather than the value. Anything asserting that a
     * value never reaches a *field* has to be a test in that module; what this covers is the free text,
     * which is where an accidental interpolation lands.
     *
     * **The whole cause chain, not just the throwable handed over, and to its end.** A caller wraps, so a
     * value that must not be logged is as likely to sit in a cause as in the outermost exception, and a
     * flattening that stops anywhere short reports absence for something present below it. That is the one
     * answer this method must never give, since every caller asserts on it being empty.
     */
    public fun everythingWritten(): String =
        records.joinToString(" ") { record ->
            record.message + " " + record.fieldNames.joinToString(" ") + " " +
                record.throwable.causeChainText()
        }
}

/**
 * The throwable and its causes as text, to the end of the chain.
 *
 * **Terminated by identity, not by a depth limit.** A caller asserts that this text does not contain a
 * value, so a walk that stops early reports absence for anything below where it stopped. Remembering the
 * throwables already seen ends a cycle on the first repeat and leaves an ordinary chain walked whole,
 * however deep it is.
 */
private fun Throwable?.causeChainText(): String {
    val parts = mutableListOf<String>()
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current = this
    while (current != null && seen.add(current)) {
        parts += current.toString()
        current = current.cause
    }
    return parts.joinToString(" ")
}
