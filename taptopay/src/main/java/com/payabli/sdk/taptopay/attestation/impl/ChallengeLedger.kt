package com.payabli.sdk.taptopay.attestation.impl

import com.payabli.sdk.taptopay.attestation.AttestationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How many spent challenges are remembered. Beyond this the oldest is forgotten.
 *
 * Bounded because an attestor can live as long as the app does, and an unbounded set of every value ever
 * seen is a leak with no ceiling. The number is generous against the traffic this guard sees: challenges
 * arrive one per attestation, not one per request.
 */
private const val REMEMBERED = 256

/**
 * Remembers which challenges have been spent, so none is offered twice.
 *
 * **A local guard.** Single use is a property of whoever issues a challenge, since only the issuer knows
 * what it handed out. What this catches is the local mistake: the same challenge passed to a second
 * attestation, which would spend a platform request on a value that cannot be honoured. Cheaper to refuse
 * here than to discover later, and the bounded memory is honest about it being a guard rather than a ledger
 * of record.
 *
 * A challenge is spent when it is **offered**, not when it succeeds. A failed attestation does not return
 * the value to the pool: the platform may well have seen it, and a caller that retries with the same value
 * is asking for exactly the replay this refuses.
 */
internal class ChallengeLedger(
    /**
     * The backing store, injectable so a test can widen the check-and-insert window and make the
     * concurrency guarantee provable rather than probabilistic.
     *
     * **Must be insertion-ordered**, because eviction is "oldest first" and reads that order rather than
     * tracking age separately. Anything else silently evicts the wrong entry.
     */
    private val spent: MutableSet<String> = LinkedHashSet(REMEMBERED),
) {
    private val mutex = Mutex()

    /** Records [value] as spent, or throws if it already was. */
    suspend fun spend(value: String) {
        mutex.withLock {
            if (!spent.add(value)) throw AttestationException.ChallengeReused()
            if (spent.size > REMEMBERED) {
                val oldest = spent.iterator()
                oldest.next()
                oldest.remove()
            }
        }
    }
}
