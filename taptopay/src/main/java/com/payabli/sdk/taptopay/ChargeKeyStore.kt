package com.payabli.sdk.taptopay

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The idempotency key of a charge that has not settled, one per entry point under one environment.
 *
 * A charge opens a transaction, waits on a card, then closes it. Every step after the open can fail
 * leaving the caller unsure whether money moved, and the recovery a host reaches for is to charge
 * again. Reusing the key of the unsettled attempt is what names that repeat as one attempt and not
 * two sales, and the service refuses such a repeat instead of opening a second transaction. Past
 * [IDEMPOTENCY_KEY_MAX_AGE] a resend opens a transaction exactly as a first send would, so nothing is
 * resent past that bound — except a key the service was seen to recognise, which never ages out.
 *
 * **In this process, not on disk.** A terminal is built per call and holds no cache, so two terminals
 * for one entry point are two objects, and the retry usually comes from the second one because the
 * screen that held the first has been rebuilt. A companion-scoped map survives that rebuild. A
 * process death does not: the token, the session and the pending-close handle all die with it, and
 * a key that outlived them belonged to a session that no longer exists.
 *
 * The key is all that is kept. It is an opaque random value naming an attempt, so holding it
 * discloses nothing about an instrument or a payer.
 *
 * [elapsedRealtimeNanos] is a monotonic clock: a wall clock moved inside a live process must not make
 * a live key look old and forfeit the refusal.
 */
internal class ChargeKeyStore(
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    private val elapsedRealtimeNanos: () -> Long,
) {
    /**
     * Serialises the read-modify-write, held on the companion so two stores over the same process map
     * take the same lock.
     */
    private val lock = SHARED_LOCK

    private val held = SHARED_HELD

    /**
     * The key [entry]'s next opening sends under [environment], and whether it is one already held.
     *
     * [Reserved.reused] is what tells a caller a refusal answers the send rather than the charge: an
     * opening refused as a repeat of a key this SDK already sent says the earlier attempt reached the
     * service, and says nothing about how it ended.
     *
     * Reading and reserving are one operation, under one lock. Split in two they leave a window where
     * two charges both find nothing held and mint separately.
     */
    suspend fun reserve(
        entry: String,
        environment: PayabliEnvironment,
    ): Reserved =
        lock.withLock {
            val now = elapsedRealtimeNanos()
            sweep(now)
            val existing = held.firstOrNull { it.matches(entry, environment) }
            if (existing != null) {
                return@withLock Reserved(existing.key, reused = true)
            }
            // Nothing live is evicted to make room. Every remaining record names a charge whose outcome
            // is still in doubt, so dropping the coldest to admit a new one loses the only thing that
            // would recognise its repeat. Refusing is the recoverable direction.
            if (held.size >= MAX) throw ChargeKeyStoreFullException(held.size)
            val minted = newKey()
            held.add(0, ChargeAttempt(entry, environment.name, minted, reservedAt = now, arrived = false))
            Reserved(minted, reused = false)
        }

    /**
     * A reserved key, and whether it was already held for this entry point under this environment.
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
     * Records that the service was seen to hold [key] for [entry] under [environment], so the sweep
     * never drops it. A `409` on a resent opening is the proof.
     *
     * Merges rather than replaces: once seen, nothing afterwards un-sees it.
     */
    suspend fun markArrived(
        entry: String,
        environment: PayabliEnvironment,
        key: String,
    ) {
        lock.withLock {
            val index = held.indexOfFirst { it.matches(entry, environment) && it.key == key }
            if (index < 0) return@withLock
            val current = held[index]
            if (!current.arrived) {
                held[index] =
                    ChargeAttempt(
                        current.entry,
                        current.environment,
                        current.key,
                        current.reservedAt,
                        arrived = true,
                    )
            }
        }
    }

    /**
     * Forgets [entry]'s key under [environment] when it is still [key], because that charge reached an
     * outcome not in doubt.
     *
     * Called only where the answer is definite. A failure that leaves it unknown whether money moved
     * keeps the key, which is the whole point of holding one.
     *
     * **[key] is checked, not assumed.** Removing whatever is held would drop an attempt still in
     * flight. A key that no longer matches has already been superseded.
     *
     * Never fails the caller: by the time this runs the charge has an outcome the caller is entitled
     * to, and raising here would report a settled payment as a failed one.
     */
    suspend fun settle(
        entry: String,
        environment: PayabliEnvironment,
        key: String,
    ) {
        lock.withLock {
            held.removeAll { it.matches(entry, environment) && it.key == key }
        }
    }

    /** Drops every non-arrived attempt past [IDEMPOTENCY_KEY_MAX_AGE]. Called with [lock] held. */
    private fun sweep(now: Long) {
        val bound = IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds
        held.removeAll { !it.arrived && now - it.reservedAt >= bound }
    }

    internal companion object {
        /**
         * How many unresolved charges can be held at once.
         *
         * A ceiling on how many entry points may be mid-charge, not a retention policy: nothing here is
         * evicted, because every record is the only thing that would recognise its charge's repeat.
         * Above the deployment that exists, which is one entry point at a time.
         */
        const val MAX: Int = 4

        /**
         * Names the encrypted entries an earlier build wrote. Removed once on wiring so a later publish
         * does not leave dead blobs beside the in-memory store; keys are never read from them.
         */
        const val LEGACY_ENTRY: String = "com.payabli.sdk.taptopay.chargekeys.v2"
        const val LEGACY_PREVIOUS_ENTRY: String = "com.payabli.sdk.taptopay.chargekeys.v1"

        private val SHARED_LOCK = Mutex()
        private val SHARED_HELD: MutableList<ChargeAttempt> = ArrayList()

        /** Drops every held attempt. Process state, so a suite sharing one JVM shares it. */
        @JvmSynthetic
        fun forgetHeld() = SHARED_HELD.clear()
    }
}

/**
 * Every slot holds a charge whose outcome is still in doubt, so there is no room to name another.
 *
 * Reached only by more entry points charging at once than a device is expected to serve, each of them
 * left unresolved. Each clears as its charge is closed. The refusal says what it is; a public error
 * code for it waits on the vocabulary that can carry one.
 */
internal class ChargeKeyStoreFullException(
    held: Int,
) : IllegalStateException("$held unresolved charges are held, so another cannot be named")

/**
 * One entry point's charge under one environment, and the key its repeat has to carry.
 *
 * Not a data class: a generated `toString` would print the entry point, which names a merchant, and
 * the key, which names an attempt at moving their money.
 *
 * [reservedAt] is when the key was chosen, on a monotonic clock. [arrived] is whether the service was
 * seen to recognise the key; once true it stays true.
 */
internal class ChargeAttempt(
    val entry: String,
    val environment: String,
    val key: String,
    val reservedAt: Long,
    val arrived: Boolean,
) {
    fun matches(
        entry: String,
        environment: PayabliEnvironment,
    ): Boolean = this.entry == entry && this.environment == environment.name
}
