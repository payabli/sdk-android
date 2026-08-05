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
 *
 * **These names can be lost without the keys being lost, and nothing here can recover from that.** The store
 * discards an entry it cannot authenticate, clears itself when its own key is gone, and resets silently when
 * its file will not parse. The signing keys live in the platform key store instead, under a different key, so
 * they survive all three. Slot state gone and keys still present reads here as an empty pending slot, and the
 * next mint adds another key beside the ones already stranded.
 *
 * Recovering means asking the key store which aliases in this namespace exist, which is why [newAlias] mints a
 * recognisable shape. That enumeration belongs to whatever holds the keys, so this type documents the hole
 * rather than claiming to close it.
 */
internal class DeviceKeySlots(
    private val storage: PayabliSecureStorage,
    /**
     * The backing store's resolved identity, which is what transitions are serialised on.
     *
     * Injected because this holds the storage interface and cannot ask it what it is backed by. The factory
     * hands out a new store object for the same file and reopening one is supported, so two instances over
     * one file must arrive here with the same value. `FileSecureStorage` takes its own identity at the same
     * composition point and for the same reason.
     */
    identity: String,
) {
    private val lock: Mutex = lockFor(identity)

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
     * The alias to mint a key under: the one already awaiting attestation, or a fresh one, which becomes
     * pending.
     *
     * Reuse is why a caller asks here instead of storing a name itself. A retry before attestation gets the
     * alias it used last time, so it attests the key it already minted. Taking a new name on each attempt
     * would leave the previous key in the store unnamed, once per attempt.
     *
     * That covers a retry, and not a lost slot. If the pending name is gone while its key is not, this mints
     * beside it. See the class.
     *
     * The alias is minted here, so no caller can supply one. Every read drops a name from outside this
     * namespace, so a name that arrived from anywhere else would be handed back as the alias to mint under
     * and then vanish from [pending], leaving that key unnamed and unpromotable.
     */
    suspend fun pendingOrNew(): String =
        transition {
            pending()?.let { return@transition it }
            val minted = DeviceKeyAliases.newAlias()
            storage.set(KEY_PENDING, minted.toByteArray(Charsets.UTF_8))
            minted
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
     * The storage layer's own lock does not cover this. It serialises each call, and what has to be atomic
     * here spans a read and a write.
     */
    private suspend fun <T> transition(block: suspend () -> T): T = lock.withLock { block() }

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

        /** One lock per backing store, shared by every instance over it. */
        private val locks = HashMap<String, Mutex>()

        /**
         * Keyed by the identity the caller already resolved, so two instances over one file serialise against
         * each other. Keyed by store object instead, they would each take their own lock and both write.
         *
         * A plain map under a monitor, matching the storage layer's own: `computeIfAbsent` is API 24 against
         * this module's floor of 23.
         */
        private fun lockFor(identity: String): Mutex = synchronized(locks) { locks.getOrPut(identity) { Mutex() } }
    }
}
