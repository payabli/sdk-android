package com.payabli.sdk.core.devicekey.impl

import com.payabli.sdk.core.storage.PayabliSecureStorage

/**
 * Which alias is the attested key and which is awaiting attestation.
 *
 * Two slots rather than one, so attesting a replacement never costs the device the key it is already using. A
 * key is minted into the pending slot, attested there, and becomes active only once the service has accepted
 * it; until then the active key keeps signing.
 *
 * **A name is never dropped without being reported.** The private half of a key never leaves the platform key
 * store, so a name is the only handle anything has on it: overwrite or delete a name silently and that key
 * stays in the store for the life of the install with nothing able to name it for deletion. Every operation
 * here either refuses to displace a name or returns the one it displaced.
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
    suspend fun pendingOrNew(candidate: String): String {
        pending()?.let { return it }
        storage.set(KEY_PENDING, candidate.toByteArray(Charsets.UTF_8))
        return candidate
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
    suspend fun promotePending(): Promotion? {
        val replaced = active()
        val promoted = read(KEY_PENDING)?.takeIf { it != replaced } ?: return null
        storage.set(KEY_ACTIVE, promoted.toByteArray(Charsets.UTF_8))
        return Promotion(activated = promoted, replaced = replaced)
    }

    /** Forgets both names, reporting them, so the keys they pointed at can be deleted rather than stranded. */
    suspend fun forget(): Forgotten {
        val forgotten = Forgotten(active = active(), pending = pending())
        storage.remove(KEY_ACTIVE)
        storage.remove(KEY_PENDING)
        return forgotten
    }

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
    }
}
