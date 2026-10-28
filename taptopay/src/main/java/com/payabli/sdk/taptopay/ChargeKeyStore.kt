package com.payabli.sdk.taptopay

import com.payabli.sdk.core.config.PayabliEnvironment
import com.payabli.sdk.core.model.PayabliAttempt
import com.payabli.sdk.core.model.PayabliErrorType
import com.payabli.sdk.core.model.PayabliGenericException
import com.payabli.sdk.core.network.IDEMPOTENCY_KEY_MAX_AGE
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The idempotency keys of unsettled charges, in this process only, newest first per entry point and environment.
 * A declared retry is handed the newest; every key ages out on [IDEMPOTENCY_KEY_MAX_AGE], as the service forgets it.
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
     * A [PayabliAttempt.Retry] is handed the newest key held for [entry] under [environment], and mints
     * only when none is held. A [PayabliAttempt.NewPayment] always mints, and leaves every held key where
     * it is: a sale of its own is not a repeat of one whose outcome is unknown.
     *
     * [Reserved.reused] is what tells a caller a refusal answers the send rather than the charge: an
     * opening refused as a repeat of a key this SDK already sent says the earlier attempt reached the
     * service, and says nothing about how it ended.
     *
     * Reading and reserving are one operation, under one lock. Split in two they leave a window where
     * two charges both find nothing held and mint separately.
     *
     * A declared retry that describes a different sale from the held one is refused before anything is sent,
     * and the held key stays for the retry that does match. A declared retry whose held key has a send under
     * way is refused with [PayabliErrorType.PAYMENT_REFERENCE_OPEN].
     *
     * @param entry the entry point the charge is for.
     * @param environment the environment the charge is sent to.
     * @param attempt whether the host declared this charge a retry.
     * @param description the sale this charge describes.
     * @return the key to send, and whether it was already held.
     */
    suspend fun reserve(
        entry: String,
        environment: PayabliEnvironment,
        attempt: PayabliAttempt,
        description: ChargeDescription? = null,
    ): Reserved =
        lock.withLock {
            val now = elapsedRealtimeNanos()
            sweep(now)
            val existing =
                held.takeIf { attempt == PayabliAttempt.Retry }?.firstOrNull { it.matches(entry, environment) }
            if (existing != null) {
                // A key has one send at a time, so every send learns its own outcome.
                if (existing.underWay) {
                    throw PayabliGenericException(
                        PayabliErrorType.PAYMENT_REFERENCE_OPEN,
                        PayabliErrorType.PAYMENT_REFERENCE_OPEN.message,
                    )
                }
                if (description != null && existing.description?.matches(description) == false) {
                    throw PayabliGenericException(
                        PayabliErrorType.RETRY_NOT_SAME_PAYMENT,
                        PayabliErrorType.RETRY_NOT_SAME_PAYMENT.message,
                    )
                }
                existing.underWay = true
                return@withLock Reserved(existing.key, reused = true, reservedAt = now)
            }
            // Nothing live is evicted to make room. Every remaining record names a charge whose outcome
            // is still in doubt, so dropping the coldest to admit a new one loses the only thing that
            // would recognise its repeat. Refusing is the recoverable direction.
            val entryPoints = held.distinctBy { it.entry to it.environment }
            if (entryPoints.none { it.matches(entry, environment) } && entryPoints.size >= MAX) {
                throw ChargeKeyStoreFullException(entryPoints.size)
            }
            val minted = newKey()
            held.add(0, ChargeAttempt(entry, environment.name, minted, now, description).apply { underWay = true })
            Reserved(minted, reused = false, reservedAt = now)
        }

    /**
     * Whether a [PayabliAttempt.Retry] for [description] would be handed a held key, without reserving one.
     *
     * @param entry the entry point the charge is for.
     * @param environment the environment the charge is sent to.
     * @param description the sale the retry describes.
     * @return true when [reserve] would resend a held key for this sale.
     */
    suspend fun holdsRetryOf(
        entry: String,
        environment: PayabliEnvironment,
        description: ChargeDescription,
    ): Boolean =
        lock.withLock {
            sweep(elapsedRealtimeNanos())
            val existing = held.firstOrNull { it.matches(entry, environment) } ?: return@withLock false
            existing.description?.matches(description) != false
        }

    /**
     * Whether the newest key held for [entry] under [environment] has a send under way.
     *
     * @param entry the entry point the charge is for.
     * @param environment the environment the charge is sent to.
     * @return true when the newest held key has been reserved and its send has not ended.
     */
    suspend fun isUnderWay(
        entry: String,
        environment: PayabliEnvironment,
    ): Boolean = lock.withLock { held.firstOrNull { it.matches(entry, environment) }?.underWay == true }

    /**
     * A reserved key, whether it was already held, and when this send reserved it. Not a `data class`, and the
     * key's getter is `@get:JvmSynthetic`, so neither a `toString` nor Java reaches the key.
     */
    internal class Reserved(
        @get:JvmSynthetic val key: String,
        val reused: Boolean,
        val reservedAt: Long,
    )

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

    /**
     * Ends a send of [key]. Once it ended unknown, drops [entry]'s other keys under [environment] and restarts
     * [key]'s clock at [arrivedAt], the send that may have reached the service. A settled [key] supersedes nothing.
     */
    suspend fun supersede(
        entry: String,
        environment: PayabliEnvironment,
        key: String,
        arrivedAt: Long? = null,
    ) {
        lock.withLock {
            val kept = held.firstOrNull { it.matches(entry, environment) && it.key == key } ?: return@withLock
            kept.underWay = false
            held.removeAll { it.matches(entry, environment) && it.key != key }
            if (arrivedAt != null) kept.reservedAt = maxOf(kept.reservedAt, arrivedAt)
        }
    }

    /**
     * Drops every attempt past [IDEMPOTENCY_KEY_MAX_AGE] that no send is using: the service forgets a key on that
     * bound, whether or not it refused a repeat of it. Called with [lock] held.
     */
    private fun sweep(now: Long) {
        val bound = IDEMPOTENCY_KEY_MAX_AGE.inWholeNanoseconds
        held.removeAll { !it.underWay && now - it.reservedAt >= bound }
    }

    internal companion object {
        /**
         * How many entry points may hold unsettled charges at once. Nothing is evicted to make room: each key is
         * the only thing that would recognise its charge's repeat, and every key ages out on its own.
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

        /** Drops every held attempt under the same lock the reserves take. Process state for tests. */
        @JvmSynthetic
        fun forgetHeld() {
            runBlocking { SHARED_LOCK.withLock { SHARED_HELD.clear() } }
        }

        /** When each held key's clock started, newest first, under the same lock. Process state for tests. */
        @JvmSynthetic
        fun heldSince(): List<Long> = runBlocking { SHARED_LOCK.withLock { SHARED_HELD.map { it.reservedAt } } }
    }
}

/**
 * Every slot holds a charge whose outcome is still in doubt, so there is no room to name another.
 *
 * Reached only by more entry points charging at once than a device is expected to serve, each of them
 * left unresolved. Each clears as its charge is closed.
 */
internal class ChargeKeyStoreFullException(
    held: Int,
) : IllegalStateException("$held unresolved charges are held, so another cannot be named")

/**
 * One entry point's charge under one environment, and the key its repeat carries, chosen at [reservedAt] on a
 * monotonic clock. Not a data class: a generated `toString` would print the merchant's entry point and the key.
 *
 * @property description the sale the key was first sent for, which a resend of it keeps.
 */
internal class ChargeAttempt(
    val entry: String,
    val environment: String,
    val key: String,
    reservedAt: Long,
    val description: ChargeDescription? = null,
) {
    /** Restarted by a resend that may have reached the service and was not refused as a repeat. */
    var reservedAt: Long = reservedAt
        internal set

    /** Whether a send of this key has not yet ended. It may still reach the service, so no sweep drops it meanwhile. */
    var underWay: Boolean = false
        internal set

    fun matches(
        entry: String,
        environment: PayabliEnvironment,
    ): Boolean = this.entry == entry && this.environment == environment.name
}
