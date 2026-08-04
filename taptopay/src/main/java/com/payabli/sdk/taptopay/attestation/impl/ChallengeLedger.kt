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
 * **This is a caller-error guard, not the security boundary.** Single use is a property the *issuer* of a
 * challenge enforces, since only the issuer knows what it handed out and what it has already retired.
 * What this catches is the local mistake: the same challenge passed to a second attestation, which would
 * spend a platform request on a value that cannot be honoured. Cheaper to refuse here than to discover
 * later, and the bounded memory is honest about it being a guard rather than a ledger of record.
 *
 * A challenge is spent when it is **offered**, not when it succeeds. A failed attestation does not return
 * the value to the pool: the platform may well have seen it, and a caller that retries with the same value
 * is asking for exactly the replay this refuses.
 */
internal class ChallengeLedger {
    private val mutex = Mutex()

    // Insertion-ordered, so eviction is "oldest first" without a second structure tracking age.
    private val spent = LinkedHashSet<String>(REMEMBERED)

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
