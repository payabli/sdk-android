package com.payabli.sdk.taptopay.enrollment

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One enrollment at a time per paypoint, across every terminal in the process.
 *
 * Keyed by entry point alone, because every terminal for a paypoint reads and writes the one stored
 * registration. A paypoint is tracked only while a caller holds or waits for its turn, so nothing here
 * outlives the work it serializes.
 */
internal object EnrollmentTurns {
    private class Turn {
        val mutex = Mutex()

        /** The callers holding or waiting for this turn. Read and written only under [turns]. */
        var callers = 0
    }

    private val turns = HashMap<String, Turn>()

    suspend fun <T> taking(
        entry: String,
        work: suspend () -> T,
    ): T {
        val turn = synchronized(turns) { turns.getOrPut(entry) { Turn() }.also { it.callers++ } }
        try {
            return turn.mutex.withLock { work() }
        } finally {
            synchronized(turns) { if (--turn.callers == 0) turns.remove(entry) }
        }
    }

    /** How many paypoints are held or waited for right now. */
    @VisibleForTesting
    fun trackedCount(): Int = synchronized(turns) { turns.size }
}
