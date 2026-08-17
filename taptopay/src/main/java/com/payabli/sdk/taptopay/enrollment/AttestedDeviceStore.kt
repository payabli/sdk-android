package com.payabli.sdk.taptopay.enrollment

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
import com.payabli.sdk.core.logging.debug
import com.payabli.sdk.core.logging.warn
import com.payabli.sdk.core.network.PayabliJson
import com.payabli.sdk.core.storage.PayabliSecureStorage
import com.payabli.sdk.core.storage.SecureStorageException
import com.payabli.sdk.taptopay.attestation.device.RedactedCause
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * The one entry [DeviceBindings] lives in, and the rules for reading it back.
 *
 * It holds the attestation bindings and nothing else. Activation state is not this SDK's to hold, so it is
 * asked for rather than kept — a copy here would be a claim nobody re-checks.
 *
 * One entry holds every binding, so there is no ordering between them and no window where some are present
 * and others are not. No rollback is needed.
 *
 * The entry name is fixed, and one name holds them all. It cannot carry the entry point, because the store
 * offers no enumeration: a name built from a value that changes leaves an entry that nothing can find and
 * nothing can remove. The entry point lives inside each binding, where a lookup answers it instead of a
 * search over names.
 *
 * No dispatcher: [PayabliSecureStorage] suspends and already holds the one it was built with. A second hop
 * onto the same pool would be a hop for the look of the thing.
 */
internal class AttestedDeviceStore(
    private val storage: PayabliSecureStorage,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) {
    /**
     * Serialises the read-modify-write that every entry point below performs.
     *
     * One entry holds every binding, so writing one means reading them all, replacing one and writing them
     * back. Two callers interleaving that lose whichever binding was read before the other's write, and a
     * lost binding is what this store exists to prevent. Callers for different entry points are exactly the
     * ones that would interleave, since nothing above serialises across them.
     *
     * **Held on the companion, not the instance**, so two stores over one backing file serialise against each
     * other. An instance lock would not: the store below is constructed per caller, and two of them reach the
     * same file through separate objects. The store's own lock does not cover this either, because it
     * serialises one operation and this is a read and a write with a decision between them.
     *
     * One lock for every instance is one lock per backing entry, because [ENTRY] is fixed: an app has one
     * such entry, so there is no second file to contend with. If that ever stops being true, key this the way
     * the persistence layer keys its own, by the store's resolved identity.
     */
    private val lock = SHARED_LOCK

    /** Whether this store has removed the superseded entry. Read and written only under [lock]. */
    private var legacyScrubbed = false

    /**
     * The binding held for [entry], or null when there is nothing usable to read.
     *
     * **Null and a failure are different answers and must stay that way.** Null means this device holds no
     * binding for [entry], and the correct response is a cold start. A failure means the store could not be
     * read *this time*, and treating that as null would run the cold sequence against a device that may
     * still be active, replacing it. The store's own contract separates the two, and this is the caller that
     * has to honour it:
     *
     * - the key was lost, or this entry alone could not be authenticated: the data is gone, so null;
     * - the record decoded to nothing recognisable: also gone, and the entry is dropped on the way out;
     * - the platform's cipher or the file was unavailable: **raised**, because the record may be perfectly
     *   fine and unreadable only for a moment.
     *
     * Reading promotes the binding to the front, so the one discarded when [DeviceBindings.MAX] is reached is
     * the one nothing has used, rather than the one enrolled longest ago.
     */
    suspend fun read(entry: String): AttestedDevice? =
        lock.withLock {
            val held = load() ?: return@withLock null
            val record = held.forEntry(entry) ?: return@withLock null
            if (!held.isMostRecent(entry)) promote(held, record)
            record
        }

    /**
     * Replaces the binding for this record's entry point, leaving every other entry point's alone.
     *
     * One call, so there is no half-written state to compensate for.
     */
    suspend fun write(record: AttestedDevice) {
        lock.withLock {
            val held = load() ?: DeviceBindings(emptyList())
            store(held.with(record))
        }
    }

    /**
     * Forgets [entry]'s binding. Never touches the key, another entry point's binding, or another consumer's
     * entries.
     *
     * Reads first, because the binding to remove has to be found among the rest. That read is also what
     * carries an older single-binding record forward, so a clear cannot leave one behind for a later read to
     * restore.
     */
    suspend fun clear(entry: String) {
        lock.withLock {
            val held = load() ?: return@withLock
            // Nothing held for this entry point, so there is nothing to remove and no reason to rewrite
            // what is held for the others.
            if (held.forEntry(entry) == null) return@withLock
            val remaining = held.without(entry)
            if (remaining.isEmpty) storage.remove(ENTRY) else store(remaining)
        }
    }

    /**
     * Everything stored, in the current shape, carrying an older record forward on the way.
     *
     * Reached by every entry point above rather than by [read] alone. A clear that skipped it would remove
     * nothing from the old entry, and the next read would carry that record forward again — restoring a
     * binding the caller had just discarded.
     */
    private suspend fun load(): DeviceBindings? {
        // The current entry answers alone whenever it is there, so the old one is never read once this
        // device has written anything, and a binding discarded since the upgrade cannot be restored from it.
        decode(ENTRY, DeviceBindings.serializer())?.let {
            scrubLegacy()
            return it
        }

        // Nothing in the current entry. An older record is the only other thing that can be there, and
        // reading it is what keeps an enrolled device enrolled across the upgrade.
        val legacy = decode(LEGACY_ENTRY, AttestedDevice.serializer()) ?: return null
        val migrated = DeviceBindings.of(legacy)

        // Written before the old entry goes, never the reverse. Interrupted between them, both are present
        // and the branch above answers from the current one. The other order loses the binding outright,
        // which is also why the removal is conditional: the old entry is the only copy until the write lands.
        if (persist(migrated)) scrubLegacy()
        return migrated
    }

    /**
     * Writes the carried-forward collection, and says whether it landed.
     *
     * **A write that fails does not withhold the binding.** The old record decoded, so this device holds a
     * usable binding and the caller can act on it; raising instead would report a record that was read
     * perfectly well as a store that could not be read, and send an upgraded install into a cold sequence or
     * a failed session over a write. The migration is how the binding is kept, not how it is answered.
     *
     * Retried on the next read, unlike the disposal removals, and for the opposite reason: this is reached
     * only while the current entry is still absent, which is exactly the condition the write exists to end.
     */
    private suspend fun persist(migrated: DeviceBindings): Boolean =
        try {
            withContext(NonCancellable) { store(migrated) }
            true
        } catch (unwritable: SecureStorageException) {
            logger.debug(RedactedCause(unwritable), LogField.safe("event", EVENT_MIGRATION_DEFERRED)) {
                "could not carry the stored device identity forward"
            }
            false
        }

    /**
     * Removes the older entry once this store has read the current one, and never fails the caller for it.
     *
     * Two entries can coexist: a migration that was interrupted between its write and this removal, and an
     * install that went back to a build writing the old shape and then forward again. In both the current
     * entry answers and the old one is never read, so what is left is an identity record naming a merchant,
     * held for as long as the app is installed and read by nothing. Removing it is the same reasoning as the
     * bound on how many bindings are kept.
     *
     * **Best effort, and after the write rather than beside it.** A removal that raised here would turn a
     * migration that had already succeeded into a failed read, and the binding it wrote is sound and
     * readable. Removing an absent entry costs no write in the layer below, which is what makes it cheap
     * enough to attempt on a read at all.
     *
     * **Attempted once, whether or not it worked.** Retrying on every later read would repeat the attempt and
     * the record of it for as long as the removal keeps failing, and buy nothing that matters: this is only
     * reached once the current entry has decoded, so the store is answering reads, and a removal that fails
     * under those conditions is not a transient the next call clears. The next store gets its own attempt,
     * which is a new session rather than a new call.
     */
    private suspend fun scrubLegacy() {
        if (legacyScrubbed) return
        legacyScrubbed = true
        removeQuietly(LEGACY_ENTRY, EVENT_LEGACY_KEPT)
    }

    /**
     * Removes an entry that is no longer usable, and never fails the caller for it.
     *
     * Both callers have already decided their answer and are disposing of something dead on the way out: a
     * record that would not decode, and an entry the current one has superseded. Raising here would turn an
     * answer the caller can act on into a failure it cannot, and would strand the entry as well, because a
     * read that raises never reaches the removal on the next attempt either.
     */
    private suspend fun removeQuietly(
        key: String,
        event: String,
    ) {
        try {
            storage.remove(key)
        } catch (unremovable: SecureStorageException) {
            logger.debug(RedactedCause(unremovable), LogField.safe("event", event)) {
                "could not remove an unusable stored entry"
            }
        }
    }

    /**
     * Moves a binding to the front, and does not fail the read that asked for it.
     *
     * The order decides only which binding is discarded first when the collection is full. Losing an update
     * to it costs at most a worse choice of which to discard, which the next enrollment repairs — where
     * failing here would turn a read that already has its answer into a cold start.
     */
    private suspend fun promote(
        held: DeviceBindings,
        record: AttestedDevice,
    ) {
        try {
            store(held.with(record))
        } catch (unwritable: SecureStorageException) {
            logger.debug(RedactedCause(unwritable), LogField.safe("event", EVENT_ORDER_UNWRITTEN)) {
                "could not record which binding was used last"
            }
        }
    }

    /** Encodes and stores the whole collection, wiping the buffer whichever way the write goes. */
    private suspend fun store(held: DeviceBindings) {
        val bytes = PayabliJson.format.encodeToString(DeviceBindings.serializer(), held).encodeToByteArray()
        try {
            storage.set(ENTRY, bytes)
        } finally {
            // The store neither copies what it is given nor wipes it, so the caller owns both ends.
            bytes.fill(0)
        }
    }

    /**
     * Reads one entry and decodes it, or answers null for every way it can turn out not to be there.
     *
     * Shared by both shapes so the four storage failures are classified in one place. A store whose key is
     * gone answers null for each entry it is asked for, which is the right answer for all of them.
     */
    private suspend fun <T> decode(
        key: String,
        serializer: KSerializer<T>,
    ): T? {
        val bytes =
            try {
                storage.get(key)
            } catch (lost: SecureStorageException.KeyInvalidated) {
                reportLost(lost, "key_invalidated")
                return null
            } catch (unreadable: SecureStorageException.ValueUnreadable) {
                reportLost(unreadable, "value_unreadable")
                return null
            }
                ?: return null

        return try {
            PayabliJson.format.decodeFromString(serializer, bytes.decodeToString())
        } catch (malformed: SerializationException) {
            // Narrowed to the serializer's own failure. A storage failure raised by the read above must
            // not be swallowed here on its way past.
            //
            // The removal is the disposal of something already known to be dead, so it does not decide the
            // answer: a record that will not decode is gone whether or not the entry holding it can be
            // dropped, and raising here would report it as a store that could not be read this time. That
            // reading wedges rather than degrades, because the caller retries, decodes the same unreadable
            // bytes and raises again, and the entry is never removed on any of those attempts.
            reportLost(malformed, "undecodable")
            removeQuietly(key, EVENT_UNREADABLE_KEPT)
            null
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * One record for every way the stored identity can turn out to be unreadable, naming which.
     *
     * The cause is redacted to its type and stack trace. By the time a decode fails the record has been
     * decrypted, and `kotlinx.serialization` quotes the input it could not parse into the exception
     * message — so an unredacted cause puts every entry point, device handle and key thumbprint held into
     * the platform log. [state] already says which case this was, which is the whole diagnostic value the
     * message carried.
     */
    private fun reportLost(
        cause: Throwable,
        state: String,
    ) = logger.warn(RedactedCause(cause), LogField.safe("event", EVENT_LOST), LogField.safe("state", state)) {
        "stored device identity is gone"
    }

    private companion object {
        /**
         * Versioned for the reason the key handle is: if the record's shape ever changes, the next version
         * takes a new name and removes this one explicitly, because this is the last code that knows it.
         */
        const val ENTRY = "com.payabli.sdk.taptopay.device.v2"

        /**
         * The single-binding shape this replaces, read once and removed.
         *
         * A separate name rather than a widened shape, because the SDK's decoder ignores unrecognized keys:
         * the older record read through the current serializer would decode without complaint and answer
         * that the device holds no bindings at all. Two names keep the two shapes from ever meeting.
         *
         * Removable once no install can still be carrying it.
         */
        const val LEGACY_ENTRY = "com.payabli.sdk.taptopay.device.v1"

        const val EVENT_LOST = "device_identity_lost"
        const val EVENT_ORDER_UNWRITTEN = "device_binding_order_unwritten"
        const val EVENT_LEGACY_KEPT = "device_identity_superseded_kept"
        const val EVENT_UNREADABLE_KEPT = "device_identity_undecodable_kept"
        const val EVENT_MIGRATION_DEFERRED = "device_identity_carry_forward_deferred"

        /** One per process, so every store over the one backing entry takes the same lock. */
        val SHARED_LOCK = Mutex()
    }
}
