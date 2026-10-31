package com.payabli.sdk.taptopay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * The charges and captured closes holding each entry point. A charge is admitted only while nothing holds its entry
 * point and no charge runs in the process; a close counts on its own entry point only and waits for the charge
 * admitted there to hold its region. Keyed exactly, so a shared stripe never refuses.
 */
internal class EntryPointHolders {
    /**
     * @property count the charges and closes holding the entry point.
     * @property charge completed once the admitted charge holds its region or ends, or null when no charge holds it.
     * @property sale the sale the admitted charge describes, or null when no charge holds it.
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

        /**
         * @property runningSale the sale of the charge holding the entry point, or null when no charge holds it,
         *   including when the running charge is on another entry point.
         */
        class Refused(
            val runningSale: ChargeDescription?,
        ) : Admission
    }

    private val guard = Any()

    private val holdings = HashMap<String, Holding>()

    private var charging: String? = null

    /**
     * Claims [entry] for a charge.
     *
     * @param entry the entry point the charge is for.
     * @param sale the sale the charge describes.
     * @return the claim, or the refusal when something holds [entry] or another entry point's charge runs, naming
     *   the sale running on [entry] when there is one.
     */
    fun admitCharge(
        entry: String,
        sale: ChargeDescription,
    ): Admission =
        synchronized(guard) {
            val holding = holdings[entry]
            if (holding != null || charging != null) return Admission.Refused(holding?.sale)
            val inRegion = CompletableDeferred<Unit>()
            holdings[entry] = Holding(1, inRegion, sale)
            charging = entry
            Admission.Admitted(inRegion)
        }

    /**
     * Counts a close on [entry].
     *
     * @param entry the entry point the close is for.
     * @return what the close waits for before taking the region: the admitted charge's signal, or null when no
     *   charge was admitted.
     */
    fun admitClose(entry: String): Deferred<Unit>? =
        synchronized(guard) {
            val held = holdings[entry]
            holdings[entry] = Holding((held?.count ?: 0) + 1, held?.charge, held?.sale)
            held?.charge
        }

    /**
     * Removes the admitted charge from [entry], with its signal and its sale, and the entry with its last holder.
     *
     * @param entry the entry point the charge is leaving.
     */
    fun releaseCharge(entry: String) {
        synchronized(guard) {
            if (charging == entry) charging = null
            leave(entry) { held -> Holding(held.count - 1, null, null) }
        }
    }

    /**
     * Removes one close from [entry], and the entry with its last holder.
     *
     * @param entry the entry point the close is leaving.
     */
    fun releaseClose(entry: String) {
        synchronized(guard) {
            leave(entry) { held -> Holding(held.count - 1, held.charge, held.sale) }
        }
    }

    /**
     * Removes one holder of [entry], or the entry with its last. Called with [guard] held.
     *
     * @param entry the entry point a holder is leaving.
     * @param remaining the holding left behind when other holders remain.
     */
    private inline fun leave(
        entry: String,
        remaining: (Holding) -> Holding,
    ) {
        val held = holdings[entry] ?: return
        if (held.count <= 1) holdings.remove(entry) else holdings[entry] = remaining(held)
    }
}
