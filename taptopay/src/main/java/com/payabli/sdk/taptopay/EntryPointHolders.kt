package com.payabli.sdk.taptopay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.util.concurrent.ConcurrentHashMap

/**
 * The charges and captured closes holding each entry point, from any terminal and environment. A charge claims
 * only an entry point nothing holds; a close counts itself in the step that finds the admitted charge, and waits
 * for that charge to hold its region. Keyed exactly, so a shared stripe never refuses.
 */
internal class EntryPointHolders {
    /**
     * @property count the charges and closes holding the entry point.
     * @property charge completed once the admitted charge holds its region or ends, or null when none was admitted.
     */
    private class Holding(
        val count: Int,
        val charge: CompletableDeferred<Unit>?,
    )

    private val holdings = ConcurrentHashMap<String, Holding>()

    /**
     * Claims [entry] for a charge.
     *
     * @param entry the entry point the charge is for.
     * @return the signal the charge completes once it holds its region, and again when it ends, or null when
     *   something already holds [entry].
     */
    fun admitCharge(entry: String): CompletableDeferred<Unit>? {
        val inRegion = CompletableDeferred<Unit>()
        return inRegion.takeIf { holdings.putIfAbsent(entry, Holding(1, inRegion)) == null }
    }

    /**
     * Counts a close on [entry].
     *
     * @param entry the entry point the close is for.
     * @return what the close waits for before taking the region: the admitted charge's signal, or null when no
     *   charge was admitted.
     */
    fun admitClose(entry: String): Deferred<Unit>? =
        holdings.compute(entry) { _, held -> Holding((held?.count ?: 0) + 1, held?.charge) }?.charge

    /**
     * Removes one holder of [entry], and the entry with its last.
     *
     * @param entry the entry point a charge or close is leaving.
     */
    fun release(entry: String) {
        holdings.compute(entry) { _, held ->
            if (held == null || held.count <= 1) null else Holding(held.count - 1, held.charge)
        }
    }
}
