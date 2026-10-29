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
     * @property sale the sale the admitted charge describes, or null when none was admitted.
     */
    private class Holding(
        val count: Int,
        val charge: CompletableDeferred<Unit>?,
        val sale: ChargeDescription?,
    )

    /** How a charge's claim on an entry point ended. */
    sealed interface Admission {
        /** @property inRegion completed by the charge once it holds its region, and again when it ends. */
        class Admitted(
            val inRegion: CompletableDeferred<Unit>,
        ) : Admission

        /** @property runningSale the sale of the charge holding the entry point, or null when no charge was admitted. */
        class Refused(
            val runningSale: ChargeDescription?,
        ) : Admission
    }

    private val holdings = ConcurrentHashMap<String, Holding>()

    /**
     * Claims [entry] for a charge.
     *
     * @param entry the entry point the charge is for.
     * @param sale the sale the charge describes.
     * @return the claim, or the refusal naming the sale already running when something holds [entry].
     */
    fun admitCharge(
        entry: String,
        sale: ChargeDescription,
    ): Admission {
        val inRegion = CompletableDeferred<Unit>()
        val holding = holdings.putIfAbsent(entry, Holding(1, inRegion, sale))
        return if (holding == null) Admission.Admitted(inRegion) else Admission.Refused(holding.sale)
    }

    /**
     * Counts a close on [entry].
     *
     * @param entry the entry point the close is for.
     * @return what the close waits for before taking the region: the admitted charge's signal, or null when no
     *   charge was admitted.
     */
    fun admitClose(entry: String): Deferred<Unit>? =
        holdings.compute(entry) { _, held -> Holding((held?.count ?: 0) + 1, held?.charge, held?.sale) }?.charge

    /**
     * Removes one holder of [entry], and the entry with its last.
     *
     * @param entry the entry point a charge or close is leaving.
     */
    fun release(entry: String) {
        holdings.compute(entry) { _, held ->
            if (held == null || held.count <= 1) null else Holding(held.count - 1, held.charge, held.sale)
        }
    }
}
