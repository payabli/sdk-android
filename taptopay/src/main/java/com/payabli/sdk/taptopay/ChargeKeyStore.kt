package com.payabli.sdk.taptopay

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.util.UUID

/**
 * The idempotency key of a charge that has not settled, one per entry point.
 *
 * A charge opens a transaction, waits on a card, then closes it. Every step after the open can fail leaving
 * the caller unsure whether money moved, and the recovery a host reaches for is to charge again. Reusing the
 * key of the unsettled attempt is what makes that repeat recognizable as a repeat instead of a second sale.
 *
 * **In storage rather than on the runner that reads it.** A terminal is built per call and holds no cache, so
 * two terminals for one entry point are two objects, and the retry usually comes from the second one because
 * the screen that held the first has been rebuilt. A key on the instance would be gone exactly when it is
 * needed. Storage also carries it across the process ending, which is the other way the holder disappears
 * mid-charge.
 *
 * **The key is all that is kept.** It is an opaque random value naming an attempt, so holding it discloses
 * nothing about an instrument or a payer. What the reader answered is never written here: that carries the
 * card's expiry and the processor's token, so its lifetime is a security property rather than bookkeeping.
 *
 * One entry holds every entry point's key, for the reason [com.payabli.sdk.taptopay.enrollment
 * .AttestedDeviceStore]'s does: the store offers no enumeration, so a name built from a value that changes
 * leaves an entry nothing can find and nothing can remove.
 */
internal class ChargeKeyStore(
    private val storage: PayabliSecureStorage,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) {
    /**
     * Serialises the read-modify-write, held on the companion for the reason the device store's is: two
     * stores over one backing entry are separate objects reaching the same file, so an instance lock would
     * let one's write drop the other's key.
     */
    private val lock = SHARED_LOCK

    /**
     * The key [entry]'s next opening sends: the unsettled attempt's, or a new one when nothing is held.
     *
     * Reading and reserving are one operation, under one lock. Split in two they leave a window where two
     * charges both find nothing held and mint separately.
     */
    suspend fun reserve(entry: String): String =
        lock.withLock {
            val held = load()
            held.forEntry(entry)?.let { return@withLock it.key }
            val minted = newKey()
            store(held.with(ChargeAttempt(entry = entry, key = minted)))
            minted
        }

    /**
     * Forgets [entry]'s key, because its charge reached an outcome that is not in doubt.
     *
     * Called only where the answer is definite. A failure that leaves it unknown whether money moved keeps
     * the key, which is the whole point of holding one.
     *
     * **Never fails the caller.** By the time this runs the charge has an outcome the caller is entitled to,
     * and raising here would report a settled payment as a failed one. What a key left behind costs is that
     * the next charge for this entry point reuses it and is suppressed; that is visible, recoverable, and
     * cheaper than turning an approval into an error.
     */
    suspend fun settle(entry: String) {
        try {
            lock.withLock {
                val held = load()
                if (held.forEntry(entry) == null) return@withLock
                val remaining = held.without(entry)
                if (remaining.isEmpty) storage.remove(ENTRY) else store(remaining)
            }
        } catch (unwritable: SecureStorageException) {
            logger.warn(RedactedCause(unwritable), LogField.safe("event", EVENT_NOT_SETTLED)) {
                "a settled charge's idempotency key could not be forgotten"
            }
        }
    }

    /**
     * Everything held, or an empty collection when there is nothing readable.
     *
     * **A store that cannot be read this time is raised rather than read as empty.** Empty means no attempt
     * is outstanding, and acting on that when a key may be sitting there is what mints a second key for one
     * attempt and charges the payer twice. So only the two failures that say the data is *gone* answer
     * empty; a cipher or a file that was unavailable for a moment stops the charge instead, because a
     * charge not taken is recoverable and a charge taken twice is not.
     */
    private suspend fun load(): ChargeAttempts {
        val bytes =
            try {
                storage.get(ENTRY)
            } catch (lost: SecureStorageException.KeyInvalidated) {
                reportGone(lost, "key_invalidated")
                return ChargeAttempts.EMPTY
            } catch (unreadable: SecureStorageException.ValueUnreadable) {
                reportGone(unreadable, "value_unreadable")
                return ChargeAttempts.EMPTY
            }
                ?: return ChargeAttempts.EMPTY

        return try {
            PayabliJson.format.decodeFromString(ChargeAttempts.serializer(), bytes.decodeToString())
        } catch (malformed: SerializationException) {
            // Narrowed to the serializer's own failure, so a storage failure raised above is not swallowed
            // on its way past. A record that will not decode is gone, and the entry holding it is dropped.
            reportGone(malformed, "undecodable")
            removeQuietly()
            ChargeAttempts.EMPTY
        } finally {
            bytes.fill(0)
        }
    }

    /** Encodes and stores the whole collection, wiping the buffer whichever way the write goes. */
    private suspend fun store(held: ChargeAttempts) {
        val bytes = PayabliJson.format.encodeToString(ChargeAttempts.serializer(), held).encodeToByteArray()
        try {
            storage.set(ENTRY, bytes)
        } finally {
            // The store neither copies what it is given nor wipes it, so the caller owns both ends.
            bytes.fill(0)
        }
    }

    /**
     * Drops an entry already known to be unusable, and never fails the caller for it.
     *
     * The record would not decode, so it is gone whether or not the entry can be removed. Raising here
     * would report that as a store which could not be read, and would strand the entry as well: the next
     * read decodes the same bytes and never reaches the removal either.
     */
    private suspend fun removeQuietly() {
        try {
            storage.remove(ENTRY)
        } catch (unremovable: SecureStorageException) {
            logger.debug(RedactedCause(unremovable), LogField.safe("event", EVENT_UNREADABLE_KEPT)) {
                "could not remove an unreadable charge key entry"
            }
        }
    }

    /**
     * One record for each way a held key turns out not to be there, naming which.
     *
     * The cause is redacted to its type and frames. `kotlinx.serialization` quotes the input it could not
     * parse, so an unredacted cause would put every entry point held into the platform log, and [state]
     * already carries the whole diagnostic value.
     */
    private fun reportGone(
        cause: Throwable,
        state: String,
    ) = logger.warn(RedactedCause(cause), LogField.safe("event", EVENT_GONE), LogField.safe("state", state)) {
        "a held charge key is gone"
    }

    private companion object {
        /**
         * Versioned the way the device record's name is: if the shape changes, the next version takes a new
         * name and removes this one explicitly, because this is the last code that knows it.
         */
        const val ENTRY = "com.payabli.sdk.taptopay.chargekeys.v1"

        const val EVENT_GONE = "ttp_charge_key_gone"
        const val EVENT_NOT_SETTLED = "ttp_charge_key_not_settled"
        const val EVENT_UNREADABLE_KEPT = "ttp_charge_key_undecodable_kept"

        /** One per process, so every store over the one backing entry takes the same lock. */
        val SHARED_LOCK = Mutex()
    }
}

/**
 * One entry point's unsettled charge, and the key its repeat has to carry.
 *
 * Not a data class: a generated `toString` would print the entry point, which names a merchant, and the key,
 * which names an attempt at moving their money.
 */
@Serializable
internal class ChargeAttempt(
    val entry: String,
    val key: String,
)

/**
 * Every unsettled charge this device holds, one per entry point, most recently reserved first.
 *
 * A list rather than a map, as the device bindings are: the order is what decides which is discarded when
 * [MAX] is reached, and a map would rest that on whatever the decoder happened to build. Lookup is by
 * [entry][ChargeAttempt.entry] either way, and at this size a scan beats a second structure.
 *
 * [attempts] carries no default. The SDK's decoder ignores keys it does not recognize, so a defaulted list
 * would let a record written in some other shape decode cleanly to an empty one — and empty here means no
 * attempt is outstanding, which is the reading that charges a payer twice.
 */
@Serializable
internal class ChargeAttempts(
    val attempts: List<ChargeAttempt>,
) {
    /** The unsettled charge for [entry], or null when none is held. */
    fun forEntry(entry: String): ChargeAttempt? = attempts.firstOrNull { it.entry == entry }

    /**
     * [attempt] at the front, replacing any held for the same entry point, capped at [MAX].
     *
     * Replace rather than insert: one entry point charges one payment at a time, and a second record for
     * the same one would make which key is read depend on where the scan started.
     */
    fun with(attempt: ChargeAttempt): ChargeAttempts =
        ChargeAttempts(
            (listOf(attempt) + attempts.filterNot { it.entry == attempt.entry }).take(MAX),
        )

    /** Without [entry]'s. Every other entry point's is left exactly where it was. */
    fun without(entry: String): ChargeAttempts = ChargeAttempts(attempts.filterNot { it.entry == entry })

    val isEmpty: Boolean get() = attempts.isEmpty()

    /** The count only. Every record names an entry point, and an entry point names a merchant. */
    override fun toString(): String = "ChargeAttempts(size=${attempts.size})"

    companion object {
        /**
         * How many unsettled charges are kept.
         *
         * A key outlives its charge only until that charge settles, so this bounds a pathological case
         * rather than ordinary use: keys left behind by charges whose outcome never arrived. Matched to the
         * device bindings, since a device that charges for several entry points holds a binding for each.
         */
        const val MAX: Int = 4

        val EMPTY: ChargeAttempts = ChargeAttempts(emptyList())
    }
}
