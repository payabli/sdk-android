package com.payabli.sdk.taptopay

import com.payabli.sdk.core.logging.LogCategory
import com.payabli.sdk.core.logging.LogField
import com.payabli.sdk.core.logging.LoggerRegistry
import com.payabli.sdk.core.logging.SdkLogger
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
            // Nothing is evicted to make room. Every record here names a charge whose outcome is still in
            // doubt, so dropping the coldest to admit a new one loses the only thing that would recognise
            // its repeat. Refusing is the recoverable direction: this needs more unsettled entry points at
            // once than a device has, and each one clears as its charge is closed.
            if (held.isFull) throw ChargeKeyStoreFullException(held.attempts.size)
            val minted = newKey()
            store(held.with(ChargeAttempt(entry = entry, key = minted)))
            minted
        }

    /**
     * Forgets [entry]'s key when it is still [key], because that charge reached an outcome not in doubt.
     *
     * Called only where the answer is definite. A failure that leaves it unknown whether money moved keeps
     * the key, which is the whole point of holding one.
     *
     * **[key] is checked, not assumed.** Two terminals for one entry point hold separate charge locks, so a
     * charge can finish after another has reserved in its place. Removing whatever is held would then drop
     * an attempt that is still in flight, and its retry would name a new one. A key that no longer matches
     * has already been superseded, and the charge that owns it is the one entitled to settle it.
     *
     * **Never fails the caller.** By the time this runs the charge has an outcome the caller is entitled to,
     * and raising here would report a settled payment as a failed one. What a key left behind costs is that
     * the next charge for this entry point reuses it and is suppressed; that is visible, recoverable, and
     * cheaper than turning an approval into an error.
     */
    suspend fun settle(
        entry: String,
        key: String,
    ) {
        try {
            lock.withLock {
                val held = load()
                if (held.forEntry(entry)?.key != key) return@withLock
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
     * Everything held. Empty only when the entry is genuinely absent.
     *
     * **Nothing readable and nothing held are different answers, and only the second one is empty.** Empty
     * says no attempt is outstanding, so a caller acting on it mints a fresh key. Reaching that conclusion
     * from a record that exists and cannot be read is what charges a payer twice: a key lost after a
     * captured sale whose close failed looks exactly like a device that has never charged.
     *
     * So an absent entry answers empty, and every other outcome raises. A charge that cannot start is
     * recoverable; a charge taken twice is not. The entry is left where it is, because removing an
     * unreadable record makes the loss permanent and hands the next charge the empty answer this refuses
     * to give.
     */
    private suspend fun load(): ChargeAttempts {
        val bytes = storage.get(ENTRY) ?: return ChargeAttempts.EMPTY

        return try {
            PayabliJson.format.decodeFromString(ChargeAttempts.serializer(), bytes.decodeToString())
        } catch (malformed: SerializationException) {
            // Narrowed to the serializer's own failure, so a storage failure raised by the read is not
            // swallowed here on its way past.
            reportUnreadable(malformed)
            throw ChargeKeyUnreadableException(RedactedCause(malformed))
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
     * The one record for a held key that cannot be read.
     *
     * The cause is redacted to its type and frames. `kotlinx.serialization` quotes the input it could not
     * parse, so an unredacted cause would put every entry point held into the platform log.
     */
    private fun reportUnreadable(cause: Throwable) =
        logger.warn(RedactedCause(cause), LogField.safe("event", EVENT_UNREADABLE)) {
            "a held charge key could not be read, so no charge can be named"
        }

    private companion object {
        /**
         * Versioned the way the device record's name is: if the shape changes, the next version takes a new
         * name and removes this one explicitly, because this is the last code that knows it.
         */
        const val ENTRY = "com.payabli.sdk.taptopay.chargekeys.v1"

        const val EVENT_UNREADABLE = "ttp_charge_key_unreadable"
        const val EVENT_NOT_SETTLED = "ttp_charge_key_not_settled"

        /** One per process, so every store over the one backing entry takes the same lock. */
        val SHARED_LOCK = Mutex()
    }
}

/**
 * A held key exists and cannot be read, so no charge can be named.
 *
 * Distinct from the store's own failures because it is not transient: the bytes will not decode on the next
 * attempt either. What clears it is resolving the transactions it named, outside the app.
 *
 * The message carries no entry point and no key: this reaches a host's crash reporter through the facade.
 */
internal class ChargeKeyUnreadableException(
    cause: Throwable,
) : IllegalStateException("a held charge key could not be read, so no charge can be named", cause)

/**
 * A decode failure with its words removed, keeping the type and the frames.
 *
 * `SerializationException.message` quotes the input it rejected, and this record holds entry points and
 * idempotency keys. The exception reaches a host as `TapToPayException.cause.cause`, so attaching the raw
 * one puts that excerpt in every crash report that walks the chain; redacting the log line alone leaves the
 * public path open. The frames are the diagnostic value and carry no text, so they stay.
 *
 * A third copy of a type `:payin` and `:core` each hold. Sharing it would mean widening a published
 * module's surface to suit an internal one, which is not the trade.
 */
internal class RedactedCause(
    original: Throwable,
) : Throwable("${original.javaClass.name} (message withheld)") {
    init {
        stackTrace = original.stackTrace
    }
}

/**
 * Every slot holds a charge whose outcome is still in doubt, so there is no room to name another.
 *
 * Reached only by more entry points charging at once than a device is expected to serve, each of them left
 * unresolved. Each clears as its charge is closed.
 */
internal class ChargeKeyStoreFullException(
    held: Int,
) : IllegalStateException("$held unresolved charges are held, so another cannot be named")

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
        ChargeAttempts(listOf(attempt) + attempts.filterNot { it.entry == attempt.entry })

    /** No room for an entry point that is not already held. Nothing here may be evicted to make room. */
    val isFull: Boolean get() = attempts.size >= MAX

    /** Without [entry]'s. Every other entry point's is left exactly where it was. */
    fun without(entry: String): ChargeAttempts = ChargeAttempts(attempts.filterNot { it.entry == entry })

    val isEmpty: Boolean get() = attempts.isEmpty()

    /** The count only. Every record names an entry point, and an entry point names a merchant. */
    override fun toString(): String = "ChargeAttempts(size=${attempts.size})"

    companion object {
        /**
         * How many unresolved charges can be held at once.
         *
         * A ceiling on how many entry points may be mid-charge, not a retention policy: nothing here is
         * evicted, because every record is the only thing that would recognise its charge's repeat. The
         * device bindings cap for the opposite reason, to stop a record of which merchants a device has
         * served accumulating, and that reasoning does not transfer to a key whose loss costs money.
         *
         * Above the deployment that exists, which is one entry point at a time.
         */
        const val MAX: Int = 4

        val EMPTY: ChargeAttempts = ChargeAttempts(emptyList())
    }
}
