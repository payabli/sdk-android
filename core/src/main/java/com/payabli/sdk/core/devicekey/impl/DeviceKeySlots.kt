package com.payabli.sdk.core.devicekey.impl

import com.payabli.sdk.core.storage.PayabliSecureStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which alias is the attested key and which is awaiting attestation.
 *
 * Two slots, so attesting a replacement never costs the device the key it is already using. A key is minted
 * into the pending slot, attested there, and becomes active only once the service has accepted it; until then
 * the active key keeps signing.
 *
 * **Nothing here reports a name it displaced, and no caller should need it to.** The private half of a key
 * never leaves the platform key store, so a name is the only handle anything has on it, and a name delivered
 * by return value is lost whenever the call does not complete: a storage write can fail partway, and the
 * process can die between the write and the caller acting on what came back. Both leave a key in the store
 * with nothing able to name it.
 *
 * So the order runs the other way. [active] and [pending] are readable before anything is written, a caller
 * that intends to displace a key reads the name first and keeps it wherever it keeps the rest of its own
 * progress, **deletes the key, and only then drops the name**. A name left pointing at a key that is already
 * gone is recoverable; a key left with no name is not.
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
     * A stored name equal to [active] is **not** pending. Promotion leaves the pending name in place, so
     * equal-to-active is how an attested key reads afterwards, and reporting it as pending would offer the key
     * already in use up to be attested a second time.
     */
    suspend fun pending(): String? = read(KEY_PENDING)?.takeIf { it != active() }

    /**
     * The alias to mint a key under: the one already awaiting attestation if there is one, otherwise
     * [candidate], which becomes pending.
     *
     * Reuse is why a caller asks here instead of storing a name itself. A retry before attestation gets the
     * alias it used last time, so it attests the key it already minted. Taking a new name on each attempt
     * would leave the previous key in the store unnamed, one per attempt, which is the accumulation the second
     * slot exists to prevent.
     */
    suspend fun pendingOrNew(candidate: String): String =
        transition {
            pending()?.let { return@transition it }
            storage.set(KEY_PENDING, candidate.toByteArray(Charsets.UTF_8))
            candidate
        }

    /**
     * Makes the pending alias the active one and returns it. Null when nothing is awaiting attestation, which
     * is a caller asking twice.
     *
     * One write. Clearing the pending name afterwards would take a second write with a window between the two,
     * and a failure there would leave the promoted alias active with the previous one already gone. Nothing
     * needs clearing, because a pending name equal to active is not pending.
     *
     * The displaced alias is not returned. A caller that means to delete that key reads [active] before
     * calling this.
     */
    suspend fun promotePending(): String? =
        transition {
            val replaced = active()
            val promoted = read(KEY_PENDING)?.takeIf { it != replaced } ?: return@transition null
            storage.set(KEY_ACTIVE, promoted.toByteArray(Charsets.UTF_8))
            promoted
        }

    /**
     * Drops both names.
     *
     * Called once the keys they named are gone, so a partial failure here leaves a name pointing at a key that
     * no longer exists, which reads as an absent key and is recoverable.
     */
    suspend fun discard() {
        transition {
            storage.remove(KEY_ACTIVE)
            storage.remove(KEY_PENDING)
        }
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
