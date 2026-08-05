package com.payabli.sdk.core.devicekey.impl

import com.payabli.sdk.core.storage.PayabliSecureStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which alias is the attested key and which is awaiting attestation.
 *
 * Two slots rather than one, so attesting a replacement never costs the device the key it is already using. A
 * key is minted into the pending slot, attested there, and becomes active only once the service has accepted
 * it; until then the active key keeps signing.
 *
 * **No call here displaces a name without returning it.** The private half of a key never leaves the platform
 * key store, so a name is the only handle anything has on it: drop a name silently and that key stays in the
 * store for the life of the install with nothing able to name it for deletion. Every operation either refuses
 * to displace a name or hands back the one it displaced.
 *
 * That holds for the call. It does not survive the process: a return value reaches nobody if the process dies
 * after the write that produced it, and the name is then gone from these slots. Closing that needs a durable
 * record of names awaiting deletion, acknowledged once the key is gone, which is a protocol for whatever
 * drives attestation and is not built here. A caller that must not strand a key across a restart reads
 * [active] and [pending] first and keeps them where it keeps the rest of its own progress.
 *
 * Only the two names live here, which is what makes them safe to keep in ordinary storage: a name is useless
 * without the key it points at.
 */
internal class DeviceKeySlots(
    private val storage: PayabliSecureStorage,
) {
    suspend fun active(): String? = read(KEY_ACTIVE)

    /**
     * The alias awaiting attestation, or null when there is none.
     *
     * A stored name equal to [active] is **not** pending. Promotion leaves the pending name in place rather
     * than deleting it, so equal-to-active is how an attested key reads afterwards, and reporting it as
     * pending would offer the key already in use up to be attested a second time.
     */
    suspend fun pending(): String? = read(KEY_PENDING)?.takeIf { it != active() }

    /**
     * The alias to mint a key under: the one already awaiting attestation if there is one, otherwise
     * [candidate], which becomes pending.
     *
     * Reuse rather than replacement, and that is why a caller asks here instead of storing a name itself. A
     * retry before attestation gets the alias it used last time, so it attests the key it already minted.
     * Taking a new name on each attempt would leave the previous key in the store unnamed, one per attempt,
     * which is the accumulation the second slot exists to prevent.
     */
    suspend fun pendingOrNew(candidate: String): String =
        transition {
            pending()?.let { return@transition it }
            storage.set(KEY_PENDING, candidate.toByteArray(Charsets.UTF_8))
            candidate
        }

    /**
     * Makes the pending alias the active one, reporting what it displaced so the caller can discard that key.
     *
     * Null when nothing is awaiting attestation, which is a caller asking twice rather than a failure.
     *
     * **One write, and that is the correctness of it.** Clearing the pending name afterwards would take a
     * second write with a window between the two: a failure in that window loses the displaced name, and the
     * retry then reads the promoted alias as already active, reports nothing displaced, and strands the key it
     * replaced. A candidate installed in the same window would be erased by the clear for the same reason.
     * Nothing needs clearing, because a pending name equal to active is not pending.
     */
    suspend fun promotePending(): Promotion? =
        transition {
            val replaced = active()
            val promoted = read(KEY_PENDING)?.takeIf { it != replaced } ?: return@transition null
            storage.set(KEY_ACTIVE, promoted.toByteArray(Charsets.UTF_8))
            Promotion(activated = promoted, replaced = replaced)
        }

    /**
     * Forgets both names and reports them, so the keys they pointed at can be deleted.
     *
     * The report reaches a caller that is still running. See the durability limit on the class.
     */
    suspend fun forget(): Forgotten =
        transition {
            val forgotten = Forgotten(active = active(), pending = pending())
            storage.remove(KEY_ACTIVE)
            storage.remove(KEY_PENDING)
            forgotten
        }

    /**
     * Serialises one read-then-write transition against every other on the same store.
     *
     * Each storage call is atomic on its own, which is not enough: two callers can both read an empty pending
     * slot and both write, and the loser's key is then in the platform store with nothing naming it.
     *
     * [active] and [pending] stay outside this. A transition calls them, and the lock is not reentrant.
     *
     * The lock is per store instance, the shape the storage cipher uses per key alias. Two stores over one
     * backing file fall outside it there too.
     */
    private suspend fun <T> transition(block: suspend () -> T): T = lockFor(storage).withLock { block() }

    private suspend fun read(key: String): String? =
        storage.get(key)?.let { bytes ->
            val alias = bytes.toString(Charsets.UTF_8)
            // A stored name that is not one of ours is not a name this can act on: it would send a caller
            // to a key store entry nothing here minted.
            alias.takeIf { DeviceKeyAliases.isDeviceKeyAlias(it) }
        }

    /** What [promotePending] did: the alias now active, and the one it displaced if there was one. */
    internal class Promotion(
        val activated: String,
        val replaced: String?,
    )

    /** What [forget] dropped, so neither key is left in the store unnamed. */
    internal class Forgotten(
        val active: String?,
        val pending: String?,
    )

    private companion object {
        const val KEY_ACTIVE = "devicekey.active"
        const val KEY_PENDING = "devicekey.pending"

        /**
         * One lock per store, so two instances over the same store serialise against each other.
         *
         * A plain map under `synchronized`, matching how the storage cipher holds its per-alias monitors.
         * `computeIfAbsent` needs a higher API level than this module's floor.
         */
        private val locks = HashMap<PayabliSecureStorage, Mutex>()

        fun lockFor(storage: PayabliSecureStorage): Mutex = synchronized(locks) { locks.getOrPut(storage) { Mutex() } }
    }
}
