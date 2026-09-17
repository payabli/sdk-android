package com.payabli.sdk.taptopay.attestation

import java.util.concurrent.atomic.AtomicReference

/**
 * Resolves the cloud project number a classic mint should use.
 *
 * The [AttestationProjectStore] holds the environment's latest number so a mint with no challenge
 * response in hand still has one. Concurrent [com.payabli.sdk.taptopay.enrollment.DeviceEnrollment]
 * instances share that store across separate `PayabliTTP.create` results, so a mint that belongs to one
 * challenge must keep the number resolved for that challenge rather than re-reading the store after
 * another enrollment may have overwritten it.
 */
internal class MintProjectResolver(
    private val latestForEnvironment: suspend () -> Long,
) {
    private val pinned = AtomicReference<Long?>(null)

    /** The number pinned for this enrollment's mint, or the environment's latest when none is. */
    suspend fun resolve(): Long = pinned.get() ?: latestForEnvironment()

    /**
     * Makes [resolve] return [number] for the duration of [block].
     *
     * Nesting is refused: one enrollment serialises its own cold path, and a second pin on the same
     * resolver would hide which challenge the mint belonged to.
     */
    suspend fun <T> whilePinned(
        number: Long,
        block: suspend () -> T,
    ): T {
        check(pinned.compareAndSet(null, number)) { "mint project already pinned" }
        try {
            return block()
        } finally {
            pinned.set(null)
        }
    }
}
