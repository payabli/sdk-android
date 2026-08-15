package com.payabli.sdk.core.logging

import com.payabli.sdk.core.logging.impl.LogSink
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Captures finished lines instead of writing them to logcat.
 *
 * `android.util.Log` is `public final` with static methods, so it cannot be stubbed, and AGP's
 * mockable `android.jar` throws "not mocked" by default. This fake is why no unit test here loads the
 * Android class and why `unitTests.returnDefaultValues` stays unset: setting it would let
 * `Log.isLoggable` silently return `false` and make every logging test vacuously pass.
 *
 * [isLoggable] uses `LogLevel`'s declaration-order `Comparable`, which is why the ladder is declared
 * least severe first.
 */
internal class RecordingLogSink(
    private val loggableFrom: LogLevel = LogLevel.DEBUG,
) : LogSink {
    internal data class Record(
        val level: LogLevel,
        val tag: String,
        val message: String,
    )

    /**
     * Thread-safe, because a transport under test writes from every request in flight at once.
     *
     * An `ArrayList` here loses records rather than reporting anything: eight writers appending 2000 lines each
     * left 4579 of 16000, and the same race throws out of `ArrayList.add` often enough to redden a run that has
     * nothing wrong with it. `LoopbackServer.requests` is a `CopyOnWriteArrayList` for the same reason.
     */
    val records: MutableList<Record> = CopyOnWriteArrayList()

    override fun isLoggable(
        level: LogLevel,
        tag: String,
    ): Boolean = level >= loggableFrom

    override fun write(
        level: LogLevel,
        tag: String,
        message: String,
    ) {
        records += Record(level, tag, message)
    }

    /** The one record written, failing if zero or more than one was. */
    fun single(): Record = records.single()
}
