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
import java.util.concurrent.TimeUnit

/**
 * The idempotency key of a charge that has not settled, one per entry point.
 *
 * A charge opens a transaction, waits on a card, then closes it. Every step after the open can fail leaving
 * the caller unsure whether money moved, and the recovery a host reaches for is to charge again. Reusing the
 * key of the unsettled attempt is what names that repeat as one attempt and not two sales, and the service
 * refuses such a repeat instead of opening a second transaction, and stops once it no longer recognises
 * the key. A key held past that is not refused and not protective: it opens a second transaction exactly as
 * a fresh one would, which is why [ChargeAttempts.MAX_AGE_MILLIS] exists and why nothing here is resent
 * past it.
 *
 * **In storage rather than on the runner that reads it.** A terminal is built per call and holds no cache, so
 * two terminals for one entry point are two objects, and the retry usually comes from the second one because
 * the screen that held the first has been rebuilt. A key on the instance would be gone exactly when it is
 * needed. Storage also carries it across the process ending, which is the other way the holder disappears
 * mid-charge.
 *
 * **The key is all that is kept.** It is an opaque random value naming an attempt, so holding it discloses
 * nothing about an instrument or a payer. What the reader answered is never written here: the provider
 * contract permits an implementation to forward the card's expiry and the processor's token in it, so its
 * lifetime is a security property rather than bookkeeping, whatever the shipped adapter narrows it to.
 *
 * One entry holds every entry point's key, for the reason [com.payabli.sdk.taptopay.enrollment
 * .AttestedDeviceStore]'s does: the store offers no enumeration, so a name built from a value that changes
 * leaves an entry nothing can find and nothing can remove.
 */
internal class ChargeKeyStore(
    private val storage: PayabliSecureStorage,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    /**
     * Wall clock, because this record outlives the process that wrote it.
     *
     * `SystemClock.elapsedRealtimeNanos` is what a bound inside one process reads, and it restarts at boot,
     * so a stamp written before a restart reads afterwards as a time in the future. A wall clock moves when
     * the device's does, which [ChargeAttempt.isLiveAt] is written to absorb.
     */
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val logger: SdkLogger = LoggerRegistry.of(LogCategory.TAP_TO_PAY),
) {
    /**
     * Serialises the read-modify-write, held on the companion for the reason the device store's is: two
     * stores over one backing entry are separate objects reaching the same file, so an instance lock would
     * let one's write drop the other's key.
     */
    private val lock = SHARED_LOCK

    /**
     * Keys this process settled and could not remove, by entry point.
     *
     * [settle] never fails its caller, so a cleanup that could not be written leaves a key named in storage
     * for a charge that already has an outcome. Resending it opens nothing and is refused, and that refusal
     * cannot be told apart from the one a live attempt earns, so without this the key is resent for as long
     * as the record stands.
     *
     * In memory and per process, which is all it can be: the write that would have persisted it is the one
     * that just failed. On the companion for the reason [lock] is, so two stores over one backing entry
     * agree about what is settled.
     *
     * A marker is read against the key held now, so one naming a key that has since been replaced answers
     * nothing and needs no clearing. An entry point holds at most one, and a later settled key for the same
     * one takes its place.
     */
    private val settled = SHARED_SETTLED

    /**
     * The key [entry]'s next opening sends, and whether it is one already held.
     *
     * [Reserved.reused] is what tells a caller a refusal answers the send rather than the charge: an opening
     * refused as a repeat of a key this SDK already sent says the earlier attempt reached the service, and
     * says nothing about how it ended.
     *
     * Reading and reserving are one operation, under one lock. Split in two they leave a window where two
     * charges both find nothing held and mint separately.
     */
    suspend fun reserve(entry: String): Reserved =
        lock.withLock {
            val now = nowMillis()
            // Expired records go before anything is decided. One names a key the service no longer knows,
            // so it protects nothing, and leaving it in would both resend it and count against the cap.
            val loaded = load()
            val held = loaded.withinWindowAt(now)
            val existing = held.forEntry(entry)
            if (existing != null && settled[entry] != existing.key) {
                // Written back, not just read that way. A stamp left in the future is re-read as fresh on
                // every reservation, so the record would never age out of a window it is always inside.
                if (held !== loaded) store(held)
                return@withLock Reserved(existing.key, reused = true)
            }
            // Nothing is evicted to make room. Every record here names a charge whose outcome is still in
            // doubt, so dropping the coldest to admit a new one loses the only thing that would recognise
            // its repeat. Refusing is the recoverable direction: this needs more unsettled entry points at
            // once than a device has, and each one clears as its charge is closed.
            //
            // An entry point already holding a record takes its slot back rather than being refused, which
            // is what a settled key reaching here needs: `with` replaces that record instead of adding one.
            if (existing == null && held.isFull) throw ChargeKeyStoreFullException(held.attempts.size)
            val minted = newKey()
            store(held.with(ChargeAttempt(entry = entry, key = minted, reservedAt = now)))
            Reserved(minted, reused = false)
        }

    /**
     * A reserved key, and whether it was already held for this entry point.
     *
     * Not a `data class`: the synthesized `toString` would put the key into anything that renders one.
     */
    internal class Reserved(
        val key: String,
        val reused: Boolean,
    )

    /**
     * Forgets [entry]'s key when it is still [key], because that charge reached an outcome not in doubt.
     *
     * Called only where the answer is definite. A failure that leaves it unknown whether money moved keeps
     * the key, which is the whole point of holding one.
     *
     * **[key] is checked, not assumed.** Two terminals for one entry point take one charge at a time, so a
     * charge settling here is normally the one that reserved. The check is what holds when that is not the
     * assumption: this store is reached from paths the charge region does not cover, and a caller that
     * builds its own is not serialized by anything this class owns. Removing whatever is held would then
     * drop an attempt still in flight, and its retry would name a new one. A key that no longer matches has
     * already been superseded, and the charge that owns it is the one entitled to settle it.
     *
     * **Never fails the caller.** By the time this runs the charge has an outcome the caller is entitled to,
     * and raising here would report a settled payment as a failed one. The key left behind is remembered in
     * [settled] instead, so the next reservation mints rather than resending a charge that is over. Without
     * that the record stands and every later charge for this entry point sends the same key and is refused,
     * which is not the recoverable cost this catch was accepted for.
     *
     * A record that will not decode raises out of [load] and is caught here too. The key stays named, since
     * removing it needs the record this cannot read.
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
            rememberSettled(entry, key)
            logger.warn(RedactedCause(unwritable), LogField.safe("event", EVENT_NOT_SETTLED)) {
                "a settled charge's idempotency key could not be forgotten"
            }
        } catch (unreadable: ChargeKeyUnreadableException) {
            rememberSettled(entry, key)
            // Safe unredacted: the message is fixed text and the cause underneath is already a
            // `RedactedCause`, so the decoder's excerpt is not on this chain.
            logger.warn(unreadable, LogField.safe("event", EVENT_NOT_SETTLED)) {
                "a settled charge's idempotency key could not be forgotten"
            }
        }
    }

    /**
     * Records that [entry]'s [key] names a charge that is over, for a reservation that storage will still
     * offer it to.
     *
     * Capped at the number of records the store itself holds, since it answers a question only about those.
     * The oldest goes when a further entry point needs a slot, which returns that one to being resent and
     * refused until its reservation expires.
     */
    private suspend fun rememberSettled(
        entry: String,
        key: String,
    ) = lock.withLock {
        if (entry !in settled && settled.size >= ChargeAttempts.MAX) {
            settled.remove(settled.keys.first())
        }
        settled[entry] = key
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
        val bytes = storage.get(ENTRY) ?: return migrated()

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

    /**
     * The previous record's keys, carried into this one's shape, or empty when there is no previous record.
     *
     * A record written before [ChargeAttempt.reservedAt] existed cannot decode into it, and a decode failure
     * here is the answer that stops a charge until the transactions are resolved outside the app. Every
     * device holding a key at upgrade would meet that, which is worse and more permanent than anything this
     * bound was added to prevent.
     *
     * **Stamped as reserved now, which reads like a window restarting and is not one.** A key carried past
     * what the service recognises opens a transaction exactly as a fresh one would, so holding it too long
     * costs nothing that minting would not also cost. What it buys is the case discarding cannot cover: an
     * upgrade landing moments after a charge was interrupted, where the key is still live and the refusal it
     * earns is the only thing standing between that payer and a second charge.
     */
    private suspend fun migrated(): ChargeAttempts {
        val bytes = storage.get(PREVIOUS_ENTRY) ?: return ChargeAttempts.EMPTY

        val carried =
            try {
                PayabliJson.format
                    .decodeFromString(PreviousChargeAttempts.serializer(), bytes.decodeToString())
                    .attempts
                    .map { ChargeAttempt(entry = it.entry, key = it.key, reservedAt = nowMillis()) }
            } catch (malformed: SerializationException) {
                reportUnreadable(malformed)
                throw ChargeKeyUnreadableException(RedactedCause(malformed))
            } finally {
                bytes.fill(0)
            }

        val moved = ChargeAttempts(carried)
        if (!moved.isEmpty) store(moved)
        // Removed only once the new record stands, so a failure between the two leaves the keys readable
        // under the old name rather than losing them entirely.
        storage.remove(PREVIOUS_ENTRY)
        return moved
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

    internal companion object {
        /**
         * Versioned the way the device record's name is: if the shape changes, the next version takes a new
         * name and removes this one explicitly, because this is the last code that knows it.
         */
        const val ENTRY = "com.payabli.sdk.taptopay.chargekeys.v2"

        /** Read once and removed, so a device upgrading keeps the key a charge in flight is holding. */
        const val PREVIOUS_ENTRY = "com.payabli.sdk.taptopay.chargekeys.v1"

        const val EVENT_UNREADABLE = "ttp_charge_key_unreadable"
        const val EVENT_NOT_SETTLED = "ttp_charge_key_not_settled"

        /** One per process, so every store over the one backing entry takes the same lock. */
        val SHARED_LOCK = Mutex()

        /**
         * One per process, for the reason [SHARED_LOCK] is. Insertion-ordered, so the oldest is the one
         * dropped when a further entry point needs a slot.
         */
        val SHARED_SETTLED: MutableMap<String, String> = LinkedHashMap()

        /**
         * Drops every marker, for a test that would otherwise inherit the previous one's.
         *
         * Process state is what this has to be, so a suite sharing a JVM shares it too.
         */
        fun forgetSettled() = SHARED_SETTLED.clear()
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
    /**
     * When this key was chosen, on a wall clock.
     *
     * No default, for the reason [ChargeAttempts.attempts] has none: a defaulted stamp would let a record
     * written in another shape decode as one reserved now, and a key that is actually old would then be
     * resent as though the service still knew it.
     */
    val reservedAt: Long,
) {
    /** This attempt with its stamp clamped into the window ending at [nowMillis]. */
    fun clampedTo(nowMillis: Long): ChargeAttempt {
        val within = reservedAt.coerceIn(nowMillis - ChargeAttempts.MAX_AGE_MILLIS, nowMillis)
        return if (within == reservedAt) this else ChargeAttempt(entry, key, within)
    }
}

/** The shape held before a reservation was stamped, read only to carry its keys into the current one. */
@Serializable
private class PreviousChargeAttempt(
    val entry: String,
    val key: String,
)

/** [PreviousChargeAttempt]s as the previous record held them. */
@Serializable
private class PreviousChargeAttempts(
    val attempts: List<PreviousChargeAttempt>,
)

/**
 * Every unsettled charge this device holds, one per entry point, most recently reserved first.
 *
 * A list rather than a map, as the device bindings are, and the order is part of the record rather than
 * whatever a decoder happened to build. **Nothing is discarded at [MAX]:** every entry names a charge whose
 * outcome is still in doubt, so `reserve` refuses a new entry point instead of evicting one. Lookup is by
 * [entry][ChargeAttempt.entry], and at this size a scan beats a second structure.
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

    /**
     * Only the records still naming a key the service is expected to recognise, with their stamps corrected
     * into the window. `this` when nothing needed either, so a caller can tell whether to write it back.
     *
     * What this drops protects nothing: past the window a held key opens a transaction exactly as a fresh
     * one would. Dropping it is also what keeps [isFull] meaning what it says, since a cap that counts
     * expired records refuses a charge to protect nothing.
     *
     * **A stamp is clamped rather than trusted**, so a device whose clock moved cannot make a record
     * permanent or expire one early. One in the future counts as reserved now and ages out a window later;
     * one older than the window is already expired. Neither needs the clock to have been right.
     */
    fun withinWindowAt(nowMillis: Long): ChargeAttempts {
        val kept =
            attempts
                .map { it.clampedTo(nowMillis) }
                .filter { nowMillis - it.reservedAt < MAX_AGE_MILLIS }
        val unchanged = kept.size == attempts.size && kept.zip(attempts).all { (a, b) -> a === b }
        return if (unchanged) this else ChargeAttempts(kept)
    }

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

        /**
         * How long a key is held, derived to land past the point the service stops recognising it rather
         * than short of it.
         *
         * The card-present opening goes through the same suppression as the card-not-present routes, so the
         * same derivation applies and the same figure comes out: what the service holds, plus the
         * transport's whole-call budget doubled for the one credential replay it may perform.
         *
         * Erring long is free. A key the service has forgotten and a key it has never seen are executed
         * alike, so a repeat past the window opens a transaction exactly as a first send would and holding
         * one too long costs nothing minting would not also cost. Erring short is not free: it forfeits the
         * refusal, which is the only thing on this path that stops a second charge.
         */
        val MAX_AGE_MILLIS: Long = TimeUnit.MINUTES.toMillis(3)

        val EMPTY: ChargeAttempts = ChargeAttempts(emptyList())
    }
}
