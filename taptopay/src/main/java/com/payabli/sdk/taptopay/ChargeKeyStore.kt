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
 * the key. Past that a resend opens a transaction exactly as a first send would, so nothing is resent past
 * [ChargeAttempts.MAX_AGE_MILLIS].
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
 *
 * [nowMillis] is a wall clock: these records outlive the process, and a monotonic one restarts at boot.
 */
internal class ChargeKeyStore(
    private val storage: PayabliSecureStorage,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
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
     * Keys this process settled and could not remove, by entry point, so a reservation finding one mints
     * rather than resending a charge that is over.
     *
     * In memory, because the write that would have persisted it is the one that failed. On the companion
     * for the reason [lock] is.
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
            val loaded = load()
            val held = loaded.withinWindowAt(now)
            // A record whose pair is marked settled names a charge that is over, so it is not a live
            // attempt: it neither answers a reservation nor holds a slot against another entry point.
            val live = held.withoutSettled(settled)
            val existing = live.forEntry(entry)
            if (existing != null) {
                // Stored, not only read: a stamp left in the future reads as fresh on every reservation.
                if (held !== loaded) {
                    store(held)
                    forgetMarkersNotIn(held)
                }
                return@withLock Reserved(existing.key, reused = true)
            }
            // Nothing live is evicted to make room. Every remaining record names a charge whose outcome is
            // still in doubt, so dropping the coldest to admit a new one loses the only thing that would
            // recognise its repeat. Refusing is the recoverable direction: this needs more unsettled entry
            // points at once than a device has, and each one clears as its charge is closed.
            if (live.isFull) throw ChargeKeyStoreFullException(live.attempts.size)
            val minted = newKey()
            // The live set is what is written, so a record found settled here is removed by the same write
            // that takes the new key, and its marker goes with it.
            val stored = live.with(ChargeAttempt(entry = entry, key = minted, reservedAt = now))
            store(stored)
            forgetMarkersNotIn(stored)
            Reserved(minted, reused = false)
        }

    /**
     * A reserved key, and whether it was already held for this entry point.
     *
     * Not a `data class`: the synthesized `toString` would put the key into anything that renders one.
     *
     * `internal` is a public name-mangled member on the JVM, so the key's getter is reachable from Java
     * without it. `@get:JvmSynthetic` is what removes it; [reused] is a flag and carries nothing.
     */
    internal class Reserved(
        @get:JvmSynthetic val key: String,
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
     * [settled], so the next reservation mints rather than resending a charge that is over.
     *
     * A record that will not decode raises out of [load] and is caught here too. The key stays named, since
     * removing it needs the record this cannot read.
     */
    suspend fun settle(
        entry: String,
        key: String,
    ) {
        // Caught inside the lock, so the marker lands before any other charge can reserve. Caught outside
        // it, the key sits in storage with nothing against it and the next reserve takes it as one to resend.
        var unforgotten: Throwable? = null
        lock.withLock {
            try {
                val held = load()
                if (held.forEntry(entry)?.key == key) {
                    val remaining = held.without(entry)
                    if (remaining.isEmpty) storage.remove(ENTRY) else store(remaining)
                    // The record is gone, so a marker an earlier attempt at this left behind names nothing.
                    // Dropped here rather than at the next reservation, which may never come.
                    if (settled[entry] == key) settled.remove(entry)
                }
            } catch (unwritable: SecureStorageException) {
                rememberSettled(entry, key)
                unforgotten = RedactedCause(unwritable)
            } catch (unreadable: ChargeKeyUnreadableException) {
                // Safe unredacted: the message is fixed text and the cause underneath is already a
                // `RedactedCause`, so the decoder's excerpt is not on this chain.
                rememberSettled(entry, key)
                unforgotten = unreadable
            }
        }
        unforgotten?.let {
            logger.warn(it, LogField.safe("event", EVENT_NOT_SETTLED)) {
                "a settled charge's idempotency key could not be forgotten"
            }
        }
    }

    /**
     * Records that [entry]'s [key] names a charge that is over, for a reservation storage will still offer
     * it to. Called with [lock] held, which is not reentrant.
     *
     * **Nothing is evicted to make room**, and dropping a marker while the record it names is still
     * stored would re-arm the key it exists to disarm. Nothing needs to be: a marker is written only for an
     * entry whose reservation succeeded, `reserve` refuses a new entry point once the live records are at
     * [ChargeAttempts.MAX], and a marker naming no stored record is dropped at the next reservation.
     */
    private fun rememberSettled(
        entry: String,
        key: String,
    ) {
        settled[entry] = key
    }

    /**
     * Drops every marker that no longer names a record in [stored].
     *
     * A marker exists to refuse a key storage is still offering. Once the record naming that key is gone it
     * refuses nothing, and it is what keeps the map bounded by the records rather than by a cap of its own.
     *
     * **Called only after the write that removed the record has landed**, never on what a read returned. A
     * record dropped from the read because its window passed is still in storage until a write says
     * otherwise, so forgetting its marker first and then failing that write leaves the record unprotected,
     * and a clock that moves back makes it live again.
     */
    private fun forgetMarkersNotIn(stored: ChargeAttempts) {
        settled.entries.removeAll { (marked, key) -> stored.forEntry(marked)?.key != key }
    }

    /**
     * Everything held. Empty only when the entry is genuinely absent.
     *
     * **Nothing readable and nothing held are different answers, and only the second one is empty.** Empty
     * says no attempt is outstanding, so a caller acting on it mints a fresh key. Reaching that conclusion
     * from a record that exists and cannot be read is what charges a payer twice: a key lost after a
     * captured sale whose close failed looks exactly like a device that has never charged.
     *
     * **The record is the truth about what is stored; the marker in [settled] is the truth about what is
     * settled.** The two cannot be merged, because a marker is written exactly when writing the record
     * failed. So a record whose pair is marked settled names no live attempt, and every site asking whether
     * one is live reads the pair rather than the record alone.
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
     * The previous record's keys in the current shape, or empty when there is no previous record.
     *
     * A record written before [ChargeAttempt.reservedAt] cannot decode into it, and a record that will not
     * decode stops every charge for this device, so the keys are carried rather than left. The carry stamps
     * now, which can hold a key past what the service recognises: sending one then opens a transaction just
     * as minting would, so it costs nothing minting would not, and it keeps the refusal for an upgrade
     * landing inside the window.
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
        // Removed only once the new record stands, so a failure between the two leaves the keys readable.
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

        private const val EVENT_UNREADABLE = "ttp_charge_key_unreadable"
        private const val EVENT_NOT_SETTLED = "ttp_charge_key_not_settled"

        /** One per process, so every store over the one backing entry takes the same lock. */
        private val SHARED_LOCK = Mutex()

        /**
         * One per process, for the reason [SHARED_LOCK] is.
         *
         * Private, because the companion is `internal` and that is a public name-mangled member on the
         * JVM: exposed, this hands a Java caller the held keys and lets it clear the markers that stop a
         * finished charge being resent.
         */
        private val SHARED_SETTLED: MutableMap<String, String> = HashMap()

        /** Drops every marker. Process state, so a suite sharing one JVM shares it. */
        @JvmSynthetic
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
 *
 * [reservedAt] is when the key was chosen, on a wall clock, and carries no default for the reason
 * [ChargeAttempts.attempts] carries none: a record written in another shape would decode as one reserved
 * now.
 */
@Serializable
internal class ChargeAttempt(
    val entry: String,
    val key: String,
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
     * The records still naming a key the service is expected to recognise, with their stamps clamped into
     * the window. `this` when neither was needed, so a caller can tell whether to write the result back.
     *
     * A stamp is clamped rather than trusted, so one in the future counts as reserved now and cannot make
     * a record permanent. A clock jumping forward past the window expires a key that is still live, and
     * what that costs is the refusal a resend would have earned.
     */
    fun withinWindowAt(nowMillis: Long): ChargeAttempts {
        val kept =
            attempts
                .map { it.clampedTo(nowMillis) }
                .filter { nowMillis - it.reservedAt < MAX_AGE_MILLIS }
        val unchanged = kept.size == attempts.size && kept.zip(attempts).all { (a, b) -> a === b }
        return if (unchanged) this else ChargeAttempts(kept)
    }

    /**
     * Without the records whose pair is marked settled in [settled]. Those name charges that are over, so
     * they are not live attempts. `this` when none of them was marked, so a caller can tell whether the
     * result is worth writing back.
     */
    fun withoutSettled(settled: Map<String, String>): ChargeAttempts {
        val live = attempts.filterNot { settled[it.entry] == it.key }
        return if (live.size == attempts.size) this else ChargeAttempts(live)
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
         * How long a key is held, derived to land past the point the service stops recognising it: what the
         * service holds, plus the transport's whole-call budget doubled for the one credential replay it
         * may perform. The card-present opening goes through the same suppression as the money-in routes.
         *
         * Erring long costs nothing, a forgotten key and an unseen one being executed alike. Erring short
         * forfeits the refusal, which is the only thing on this path that stops a second charge.
         */
        val MAX_AGE_MILLIS: Long = TimeUnit.MINUTES.toMillis(3)

        val EMPTY: ChargeAttempts = ChargeAttempts(emptyList())
    }
}
