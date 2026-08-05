package com.payabli.sdk.core.devicekey.impl

import com.payabli.sdk.core.storage.PayabliSecureStorage

/**
 * Which alias is the attested key and which is awaiting attestation.
 *
 * Two slots rather than one, so attesting a replacement never costs the device the key it is already using.
 * A key is minted into [pending], attested there, and only becomes [active] once the service has accepted
 * it; until then the active key keeps signing. A retry before attestation succeeds finds the pending alias
 * and reuses the same hardware key rather than minting a second one, which would leave an unattested key in
 * the store on every attempt.
 *
 * **Only the two names live here.** The keys themselves are in the platform key store, which is what makes
 * this safe to keep in ordinary storage: a name is useless without the key it points at, and the private
 * half never leaves the store.
 */
internal class DeviceKeySlots(
    private val storage: PayabliSecureStorage,
) {
    suspend fun active(): String? = read(KEY_ACTIVE)

    suspend fun pending(): String? = read(KEY_PENDING)

    suspend fun setPending(alias: String) {
        storage.set(KEY_PENDING, alias.toByteArray(Charsets.UTF_8))
    }

    /**
     * Makes the pending alias the active one, reporting what it replaced so the caller can discard that key.
     *
     * Null when nothing is pending, which is a caller asking to promote twice rather than a failure.
     *
     * The write order is deliberate: active is set before pending is cleared, so an interruption between
     * the two leaves both names pointing at the same usable key. Clearing first would leave a promoted key
     * with nothing naming it and the old key still active.
     */
    suspend fun promotePending(): Promotion? {
        val promoted = pending() ?: return null
        val replaced = active()
        storage.set(KEY_ACTIVE, promoted.toByteArray(Charsets.UTF_8))
        storage.remove(KEY_PENDING)
        return Promotion(activated = promoted, replaced = replaced?.takeIf { it != promoted })
    }

    /** Forgets both names. The keys they pointed at are the caller's to delete. */
    suspend fun clear() {
        storage.remove(KEY_ACTIVE)
        storage.remove(KEY_PENDING)
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

    private companion object {
        const val KEY_ACTIVE = "devicekey.active"
        const val KEY_PENDING = "devicekey.pending"
    }
}
